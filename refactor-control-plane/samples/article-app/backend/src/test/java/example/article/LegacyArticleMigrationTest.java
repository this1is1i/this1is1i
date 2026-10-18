package example.article;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class LegacyArticleMigrationTest {
    JdbcTemplate source,target;LegacyArticleMigration tool;
    LegacyArticleMigration.Policy policy=new LegacyArticleMigration.Policy(ZoneId.of("Europe/Istanbul"),ArticleStatus.PUBLISHED,Map.of(3L,"alice"));
    @BeforeEach void fixture() {
        source=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:source-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa",""));
        target=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:target-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa",""));
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(target.getDataSource());
        source.execute("CREATE TABLE blogs(Id BIGINT PRIMARY KEY,UserId BIGINT,BlogUrl VARCHAR(255))");
        source.execute("CREATE TABLE menus(Id BIGINT PRIMARY KEY,MenuName VARCHAR(255))");
        source.execute("CREATE TABLE blogsmenus(Id BIGINT PRIMARY KEY,BlogId BIGINT,MenuId BIGINT)");
        source.execute("CREATE TABLE posts(Id BIGINT PRIMARY KEY,PostTitle VARCHAR(510),PostContent CLOB,RequestMapping VARCHAR(255),BlogMenuId BIGINT,DateTime TIMESTAMP(9))");
        source.update("INSERT INTO blogs VALUES(3,99,'eyupgevenim')");source.update("INSERT INTO menus VALUES(1,'Anasayfa')");source.update("INSERT INTO blogsmenus VALUES(15,3,1)");
        source.update("INSERT INTO posts VALUES(16,'原文🙂','<p><strong>原文</strong><script>bad()</script></p>','Cevik-Yazilim',15,?)",LocalDateTime.parse("2016-12-15T10:48:04.123456789"));
        tool=new LegacyArticleMigration(source,target,Set.of("alice","bob"));
    }
    @Test void copiesRawContentAssociationsTimeAndStableRouteIdempotently() {
        var preview=tool.preview(policy);assertEquals(1,preview.sourceCount());assertEquals(1,preview.pending());assertTrue(preview.issues().isEmpty());
        assertEquals(0,count("modern_articles"));
        var result=tool.apply(policy,preview.fingerprint(),"operator");assertEquals(1,result.imported());
        var articles=new ArticleService(target);var article=articles.findAll("alice").getFirst();
        assertEquals("/eyupgevenim/Anasayfa/Cevik-Yazilim",article.publicPath());assertTrue(article.content().contains("<script>"));
        assertFalse(articles.published(article.id()).html().contains("<script>"));
        assertEquals(Instant.parse("2016-12-15T07:48:04.123456Z"),article.updatedAt());
        assertTrue(target.queryForObject("SELECT source_json FROM legacy_post_import WHERE legacy_id=16",String.class).contains("123456789"));
        assertEquals(0,tool.apply(policy,preview.fingerprint(),"operator").imported());assertEquals(1,count("article_migration_run"));
        assertEquals(1,source.queryForObject("SELECT COUNT(*) FROM posts",Integer.class));
    }
    @Test void preservesEditsAndSoftDeletionAndRejectsChangedSource() {
        var preview=tool.preview(policy);tool.apply(policy,preview.fingerprint(),"operator");
        var service=new ArticleService(target);var article=service.findAll("alice").getFirst();
        service.update("alice",article.id(),new ArticleCommand("edited","<p>edited</p>",ArticleStatus.DRAFT,null,1L));
        assertFalse(tool.preview(policy).issues().isEmpty());assertThrows(IllegalStateException.class,()->tool.apply(policy,preview.fingerprint(),"operator"));
        service.delete("alice",article.id(),2L);assertFalse(tool.preview(policy).issues().isEmpty());
        assertThrows(IllegalStateException.class,()->tool.apply(policy,preview.fingerprint(),"operator"));
        assertEquals(1,count("legacy_post_import"));assertTrue(target.queryForObject("SELECT deleted FROM modern_articles WHERE id=?",Boolean.class,article.id()));
        source.update("UPDATE posts SET PostTitle='changed' WHERE Id=16");assertNotEquals(preview.fingerprint(),tool.preview(policy).fingerprint());
    }
    @Test void rejectsInvalidPolicyMissingAssociationsAndAmbiguousTimeBeforeWriting() {
        assertThrows(IllegalArgumentException.class,()->new LegacyArticleMigration.Policy(null,ArticleStatus.DRAFT,Map.of(3L,"alice")));
        assertFalse(tool.preview(new LegacyArticleMigration.Policy(ZoneId.of("UTC"),ArticleStatus.DRAFT,Map.of(3L,"unknown"))).issues().isEmpty());
        source.update("UPDATE posts SET BlogMenuId=999 WHERE Id=16");assertFalse(tool.preview(policy).issues().isEmpty());assertEquals(0,count("modern_articles"));
        source.update("UPDATE posts SET BlogMenuId=15,DateTime=? WHERE Id=16",LocalDateTime.parse("2024-10-27T02:30:00"));
        assertFalse(tool.preview(new LegacyArticleMigration.Policy(ZoneId.of("Europe/Berlin"),ArticleStatus.DRAFT,Map.of(3L,"alice"))).issues().isEmpty());
    }
    @Test void rollsBackArticlesMappingsBlogsAndAuditWhenMappingInsertFails() {
        var preview=tool.preview(policy);
        target.execute("ALTER TABLE legacy_post_import ADD CONSTRAINT injected_failure CHECK(legacy_id<>16)");
        assertThrows(RuntimeException.class,()->tool.apply(policy,preview.fingerprint(),"operator"));
        for(String table:List.of("modern_articles","article_blogs","article_menus","legacy_post_import","article_migration_run"))assertEquals(0,count(table),table);
    }
    @Test void refusesStalePreviewAndConcurrentApplyCreatesOneBatch() throws Exception {
        var preview=tool.preview(policy);source.update("UPDATE posts SET PostTitle='new' WHERE Id=16");
        assertThrows(IllegalStateException.class,()->tool.apply(policy,preview.fingerprint(),"operator"));
        var current=tool.preview(policy);
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a=executor.submit(()->tool.apply(policy,current.fingerprint(),"operator"));var b=executor.submit(()->tool.apply(policy,current.fingerprint(),"operator"));
            assertEquals(1,a.get().imported()+b.get().imported());
        }
        assertEquals(1,count("article_migration_run"));assertEquals(1,count("legacy_post_import"));
    }
    int count(String table) {return target.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
}
