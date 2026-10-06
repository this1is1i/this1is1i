package io.refactorcontrolplane.article;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"control-plane.identity-file=classpath:review-identities.json",
        "spring.datasource.url=jdbc:h2:mem:article-migration-test"})
@AutoConfigureMockMvc
class LegacyPostMigrationContractTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired ArticleService articles;
    static final String ROOT = "/api/v1/article-migration/";
    static final String POLICY = "\"sourceZone\":\"Europe/Istanbul\",\"targetStatus\":\"DRAFT\"";

    @BeforeEach void source() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS posts (Id BIGINT PRIMARY KEY, PostTitle VARCHAR(255), "
                + "PostContent CLOB, RequestMapping VARCHAR(255), BlogMenuId BIGINT, DateTime TIMESTAMP)");
        jdbc.update("DELETE FROM posts");
        for (String table : new String[]{"LEGACY_POST_IMPORT", "ARTICLE_MIGRATION_RUN"}) {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_name = ?", Integer.class, table) > 0)
                jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("DELETE FROM modern_articles");
        insert(16, "Çevik 中文", "<p>don't truncate</p>\r\n", "2016-12-15 10:48:04");
    }

    @Test void previewIsReadOnlyAndApplyPreservesSourceAndGeneratedIdMapping() throws Exception {
        Article existing = articles.create(new ArticleCommand("existing", "keep", ArticleStatus.PUBLISHED));
        var before = jdbc.queryForList("SELECT * FROM posts");
        var preview = request("preview", "{" + POLICY + "}", 200);
        assertEquals(1, preview.path("pending").asInt());
        assertEquals(1, articles.findAll().size());
        var result = apply(preview, 200);
        assertEquals(1, result.path("imported").asInt());
        assertEquals(0, result.path("alreadyImported").asInt());
        long target = jdbc.queryForObject("SELECT article_id FROM legacy_post_import WHERE legacy_id=16", Long.class);
        assertNotEquals(existing.id(), target);
        Article article = articles.find(target);
        assertEquals("Çevik 中文", article.title());
        assertEquals("<p>don't truncate</p>\r\n", article.content());
        assertEquals("2016-12-15T07:48:04Z", article.updatedAt().toString());
        assertEquals(ArticleStatus.DRAFT, article.status());
        assertEquals("route-16", jdbc.queryForObject("SELECT request_mapping FROM legacy_post_import WHERE legacy_id=16", String.class));
        assertEquals(15, jdbc.queryForObject("SELECT blog_menu_id FROM legacy_post_import WHERE legacy_id=16", Long.class));
        assertEquals(before, jdbc.queryForList("SELECT * FROM posts"));
        assertEquals(0, apply(preview, 200).path("imported").asInt());
        assertEquals(2, articles.findAll().size());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM article_migration_run", Integer.class));
    }

    @Test void sourceChangeAfterPreviewCannotImportDifferentData() throws Exception {
        var preview = request("preview", "{" + POLICY + "}", 200);
        jdbc.update("UPDATE posts SET PostTitle='changed' WHERE Id=16");
        apply(preview, 409);
        assertTrue(articles.findAll().isEmpty());
    }

    @Test void invalidRowBlocksWholeBatchWithoutTruncation() throws Exception {
        insert(17, "valid", "second", "2016-12-15 11:00:00");
        jdbc.update("UPDATE posts SET PostTitle='   ' WHERE Id=17");
        var preview = request("preview", "{" + POLICY + "}", 200);
        assertTrue(preview.path("issues").size() > 0);
        apply(preview, 409);
        assertTrue(articles.findAll().isEmpty());
    }

    @Test void migratedTargetChangesAndDeletionNeverGetOverwrittenOrResurrected() throws Exception {
        var preview = request("preview", "{" + POLICY + "}", 200);
        apply(preview, 200);
        long target = jdbc.queryForObject("SELECT article_id FROM legacy_post_import WHERE legacy_id=16", Long.class);
        articles.update(target, new ArticleCommand("edited", "new content", ArticleStatus.PUBLISHED));
        apply(preview, 409);
        assertEquals("edited", articles.find(target).title());
        articles.delete(target);
        apply(preview, 409);
        assertTrue(articles.findAll().isEmpty());
    }

    @Test void ambiguousOrMissingLocalTimeIsReportedWithoutGuessing() throws Exception {
        jdbc.update("UPDATE posts SET DateTime=TIMESTAMP '2024-10-27 02:30:00'");
        var overlap = request("preview", "{\"sourceZone\":\"Europe/Berlin\",\"targetStatus\":\"DRAFT\"}", 200);
        assertTrue(overlap.path("issues").toString().contains("ambiguous"));
        jdbc.update("UPDATE posts SET DateTime=TIMESTAMP '2024-03-31 02:30:00'");
        var gap = request("preview", "{\"sourceZone\":\"Europe/Berlin\",\"targetStatus\":\"DRAFT\"}", 200);
        assertTrue(gap.path("issues").toString().contains("ambiguous"));
    }

    @Test void requiresManagerIdentityAndExplicitPolicy() throws Exception {
        mvc.perform(post(ROOT + "preview").contentType(MediaType.APPLICATION_JSON).content("{" + POLICY + "}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(ROOT + "apply").header("Authorization", "Bearer developer-test-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{" + POLICY + "}"))
                .andExpect(status().isForbidden());
        request("preview", "{}", 400);
        request("apply", "{" + POLICY + "}", 400);
    }

    @Test void malformedJsonUsesTheDocumentedErrorShape() throws Exception {
        assertTrue(request("preview", "{", 400).path("error").isTextual());
    }

    @Test void excessiveBatchFailsBeforeAnyArticleIsWritten() throws Exception {
        jdbc.update("INSERT INTO posts SELECT X, 'title', 'body', 'route', 15, CURRENT_TIMESTAMP FROM SYSTEM_RANGE(100, 10100)");
        request("preview", "{" + POLICY + "}", 409);
        assertTrue(articles.findAll().isEmpty());
    }

    @Test void unavailableSourceReturnsAConflictWithoutCreatingSourceTables() throws Exception {
        jdbc.execute("ALTER TABLE posts RENAME TO test_unavailable_posts");
        try {
            request("preview", "{" + POLICY + "}", 409);
            assertTrue(articles.findAll().isEmpty());
        } finally { jdbc.execute("ALTER TABLE test_unavailable_posts RENAME TO posts"); }
    }

    @Test void databaseFailureRollsBackEveryArticleMappingAndAuditRecord() throws Exception {
        insert(17, "fail-second", "second", "2016-12-15 11:00:00");
        var preview = request("preview", "{" + POLICY + "}", 200);
        jdbc.execute("ALTER TABLE modern_articles ADD CONSTRAINT test_import_failure CHECK (title <> 'fail-second')");
        try {
            apply(preview, 409);
            assertTrue(articles.findAll().isEmpty());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM legacy_post_import", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM article_migration_run", Integer.class));
        } finally { jdbc.execute("ALTER TABLE modern_articles DROP CONSTRAINT test_import_failure"); }
    }

    @Test void missingImportedSourceDoesNotProduceNegativeCounts() throws Exception {
        var preview = request("preview", "{" + POLICY + "}", 200);
        apply(preview, 200);
        jdbc.update("DELETE FROM posts");
        var missing = request("preview", "{" + POLICY + "}", 200);
        assertEquals(0, missing.path("alreadyImported").asInt());
        assertEquals(1, missing.path("issues").size());
    }

    @Test void sourceAndTargetPreserveSubMicrosecondTime() throws Exception {
        jdbc.execute("ALTER TABLE posts ALTER COLUMN DateTime TIMESTAMP(9)");
        jdbc.update("UPDATE posts SET DateTime=TIMESTAMP '2016-12-15 10:48:04.123456789'");
        var preview = request("preview", "{" + POLICY + "}", 200);
        apply(preview, 200);
        assertEquals(java.time.LocalDateTime.parse("2016-12-15T10:48:04.123456789"),
                jdbc.queryForObject("SELECT source_datetime FROM legacy_post_import", java.time.LocalDateTime.class));
        assertEquals("2016-12-15T07:48:04.123456789Z", articles.findAll().getFirst().updatedAt().toString());
    }

    @Test void concurrentApplyHasExactlyOneImportAndAnIdempotentRetry() throws Exception {
        var preview = request("preview", "{" + POLICY + "}", 200);
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(() -> apply(preview, 200));
            var second = pool.submit(() -> apply(preview, 200));
            assertEquals(1, first.get().path("imported").asInt() + second.get().path("imported").asInt());
        }
        assertEquals(1, articles.findAll().size());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM legacy_post_import", Integer.class));
    }

    @Test void pinnedSamplePostsRetainAllFieldsAndRemainEditableThroughTheArticleModel() throws Exception {
        var snapshot = io.refactorcontrolplane.analysis.GitAnalysisSnapshot.capture(java.nio.file.Path.of("../samples/workspaces/springmvc-blog"));
        assertEquals("5e62608af333b5757bf767e05b19e2b0d56f0afb", snapshot.commit());
        String dump = snapshot.entries().stream().filter(entry -> entry.path().equals("Blog/src/main/resources/db/blogdb.sql"))
                .findFirst().orElseThrow().text().replace("\r\n", "\n");
        int begin = dump.indexOf("INSERT INTO `posts`");
        String insert = dump.substring(begin, dump.indexOf(";\n", begin) + 1);
        // The pinned dump uses MySQL's backslash-escaped CR/LF. H2 needs literal line breaks.
        insert = insert.replace("\\r\\n", "\r\n");
        jdbc.update("DELETE FROM posts");
        jdbc.execute(insert);
        var source = jdbc.queryForList("SELECT Id, PostTitle, CAST(PostContent AS VARCHAR) AS body, RequestMapping, BlogMenuId, DateTime FROM posts ORDER BY Id");
        assertTrue(source.size() >= 6);
        var preview = request("preview", "{" + POLICY + "}", 200);
        assertEquals(0, preview.path("issues").size());
        assertEquals(source.size(), apply(preview, 200).path("imported").asInt());
        for (var row : source) {
            long id = ((Number) row.get("ID")).longValue();
            var mapping = jdbc.queryForMap("SELECT * FROM legacy_post_import WHERE legacy_id=?", id);
            long articleId = ((Number) mapping.get("ARTICLE_ID")).longValue();
            Article article = articles.find(articleId);
            assertEquals(row.get("POSTTITLE"), article.title());
            assertEquals(row.get("BODY"), article.content());
            assertEquals(row.get("REQUESTMAPPING"), mapping.get("REQUEST_MAPPING"));
            assertEquals(row.get("BLOGMENUID"), mapping.get("BLOG_MENU_ID"));
            assertEquals(row.get("DATETIME"), mapping.get("SOURCE_DATETIME"));
            articles.update(articleId, new ArticleCommand(article.title(), article.content(), ArticleStatus.PUBLISHED));
            assertEquals(ArticleStatus.PUBLISHED, articles.find(articleId).status());
        }
        assertEquals(source, jdbc.queryForList("SELECT Id, PostTitle, CAST(PostContent AS VARCHAR) AS body, RequestMapping, BlogMenuId, DateTime FROM posts ORDER BY Id"));
    }

    private void insert(long id, String title, String body, String time) {
        jdbc.update("INSERT INTO posts VALUES (?, ?, ?, ?, ?, CAST(? AS TIMESTAMP))", id, title, body, "route-" + id, 15, time);
    }
    private JsonNode apply(JsonNode preview, int expected) throws Exception {
        return request("apply", "{" + POLICY + ",\"expectedFingerprint\":\"" + preview.path("fingerprint").asText() + "\"}", expected);
    }
    private JsonNode request(String operation, String payload, int expected) throws Exception {
        return mapper.readTree(mvc.perform(post(ROOT + operation).header("Authorization", "Bearer manager-test-token")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString());
    }
}
