package io.refactorcontrolplane.article;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** One fixed legacy posts snapshot in the local reference H2 database; no remote DB access. */
@Service
public class LegacyPostMigrationService {
    private final JdbcTemplate jdbc;
    private final ArticleService articles;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;

    public LegacyPostMigrationService(JdbcTemplate jdbc, ArticleService articles, ObjectMapper mapper,
            PlatformTransactionManager transactions) {
        this.jdbc = jdbc; this.articles = articles; this.mapper = mapper;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.transaction.setTimeout(30);
    }

    public Report preview(Policy policy) {
        return transaction.execute(status -> inspect(policy).report(0));
    }

    public Report apply(Policy policy, String expectedFingerprint, String actor) {
        if (expectedFingerprint == null || !expectedFingerprint.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("expectedFingerprint must be the SHA-256 returned by preview");
        return transaction.execute(status -> {
            jdbc.queryForObject("SELECT id FROM article_migration_lock WHERE id=1 FOR UPDATE", Integer.class);
            Inspection inspection = inspect(policy);
            if (!inspection.fingerprint.equals(expectedFingerprint)) throw new IllegalStateException("Source or policy changed; preview again");
            if (!inspection.issues.isEmpty()) throw new IllegalStateException("Migration has conflicts; inspect preview issues");
            int imported = 0;
            for (LegacyPost source : inspection.pending) {
                Instant time = instant(source, policy);
                var key = new GeneratedKeyHolder();
                jdbc.update(connection -> {
                    var statement = connection.prepareStatement(
                            "INSERT INTO modern_articles(title, content, status, updated_at) VALUES (?, ?, ?, ?)", new String[]{"id"});
                    statement.setString(1, source.title); statement.setString(2, source.content);
                    statement.setString(3, policy.targetStatus.name()); statement.setTimestamp(4, Timestamp.from(time));
                    return statement;
                }, key);
                if (key.getKey() == null) throw new IllegalStateException("Database did not return the imported article ID");
                long articleId = key.getKey().longValue();
                Article article = articles.find(articleId);
                jdbc.update("""
                        INSERT INTO legacy_post_import(legacy_id, article_id, source_fingerprint, target_fingerprint,
                          source_zone, target_status, source_title, source_content, request_mapping, blog_menu_id, source_datetime)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, source.id, articleId, fingerprint(source, policy), targetFingerprint(article),
                        policy.sourceZone.getId(), policy.targetStatus.name(), source.title, source.content,
                        source.requestMapping, source.blogMenuId, source.dateTime);
                imported++;
            }
            if (jdbc.queryForObject("SELECT COUNT(*) FROM article_migration_run WHERE fingerprint=?", Integer.class, expectedFingerprint) == 0)
                jdbc.update("""
                        INSERT INTO article_migration_run(fingerprint, actor, source_zone, target_status, source_count, imported_count)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """, expectedFingerprint, actor, policy.sourceZone.getId(), policy.targetStatus.name(), inspection.rows.size(), imported);
            return new Report(expectedFingerprint, inspection.rows.size(), 0, inspection.alreadyImported, imported, List.of());
        });
    }

    private Inspection inspect(Policy policy) {
        Objects.requireNonNull(policy);
        // Bound LOB materialization in SQL before getString allocates the source contents.
        var size = jdbc.queryForMap("""
                SELECT COUNT(*) AS row_count, COALESCE(SUM(COALESCE(CHAR_LENGTH(PostTitle), 0)
                  + COALESCE(CHAR_LENGTH(PostContent), 0) + COALESCE(CHAR_LENGTH(RequestMapping), 0)), 0) AS text_size FROM posts
                """);
        if (((Number) size.get("ROW_COUNT")).longValue() > 10000
                || ((Number) size.get("TEXT_SIZE")).longValue() > 16 * 1024 * 1024)
            throw new IllegalStateException("Reference migration limit is 10000 rows and 16 MiB of text; source exceeds the preflight limit");
        List<LegacyPost> rows = jdbc.query("""
                SELECT Id, PostTitle, PostContent, RequestMapping, BlogMenuId, DateTime FROM posts ORDER BY Id
                FETCH FIRST 10001 ROWS ONLY
                """, (row, number) -> new LegacyPost(row.getObject(1, Long.class), row.getString(2), row.getString(3),
                row.getString(4), row.getObject(5, Long.class), row.getObject(6, LocalDateTime.class)));
        if (rows.size() > 10000) throw new IllegalStateException("Reference migration limit is 10000 rows; split only with a reviewed migration plan");
        var issues = new ArrayList<Issue>();
        var pending = new ArrayList<LegacyPost>();
        int alreadyImported = 0;
        long bytes = 0;
        Set<Long> seen = new HashSet<>();
        for (LegacyPost source : rows) {
            for (String text : new String[]{source.title, source.content, source.requestMapping})
                bytes += text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > 16 * 1024 * 1024) throw new IllegalStateException("Reference migration content limit is 16 MiB");
            if (!seen.add(source.id)) issues.add(new Issue(source.id, "Duplicate legacy ID"));
            if (source.id == null || source.id < 1 || source.title == null || source.title.isBlank() || source.title.length() > 255
                    || source.content == null || source.content.isBlank() || source.requestMapping == null
                    || source.requestMapping.length() > 255 || source.blogMenuId == null || source.blogMenuId < 1 || source.dateTime == null) {
                issues.add(new Issue(source.id, "Invalid required source field; no trimming or truncation is permitted"));
                continue;
            }
            if (policy.sourceZone.getRules().getValidOffsets(source.dateTime).size() != 1) {
                issues.add(new Issue(source.id, "Missing or ambiguous local time; choose an explicit fixed offset after review"));
                continue;
            }
            var existing = jdbc.queryForList("SELECT article_id, source_fingerprint, target_fingerprint FROM legacy_post_import WHERE legacy_id=?", source.id);
            if (existing.isEmpty()) { pending.add(source); continue; }
            var mapping = existing.getFirst();
            if (!fingerprint(source, policy).equals(mapping.get("SOURCE_FINGERPRINT"))) {
                issues.add(new Issue(source.id, "Previously imported source or policy changed"));
                continue;
            }
            try {
                Article target = articles.find(((Number) mapping.get("ARTICLE_ID")).longValue());
                if (!targetFingerprint(target).equals(mapping.get("TARGET_FINGERPRINT")))
                    issues.add(new Issue(source.id, "Target changed; preserving the current article"));
                else alreadyImported++;
            } catch (ArticleNotFoundException exception) {
                issues.add(new Issue(source.id, "Target deleted; preserving the deletion"));
            }
        }
        for (Long priorId : jdbc.queryForList("SELECT legacy_id FROM legacy_post_import", Long.class)) {
            if (!seen.contains(priorId)) issues.add(new Issue(priorId, "Previously imported source is missing"));
        }
        String fingerprint = hash(List.of("springmvc-blog/posts/v1", policy.sourceZone.getId(), policy.targetStatus.name(), rows));
        return new Inspection(rows, fingerprint, pending, alreadyImported, issues);
    }

    private static Instant instant(LegacyPost post, Policy policy) {
        return post.dateTime.toInstant(policy.sourceZone.getRules().getValidOffsets(post.dateTime).getFirst());
    }
    private String fingerprint(LegacyPost post, Policy policy) { return hash(List.of(post, policy.sourceZone.getId(), policy.targetStatus.name())); }
    private String targetFingerprint(Article article) { return hash(article); }
    private String hash(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(value))); }
        catch (java.io.IOException | java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException("Cannot fingerprint migration", exception); }
    }
    public record Policy(ZoneId sourceZone, ArticleStatus targetStatus) {
        public Policy { Objects.requireNonNull(sourceZone); Objects.requireNonNull(targetStatus); }
    }
    public record Issue(Long legacyId, String reason) {}
    public record Report(String fingerprint, int sourceCount, int pending, int alreadyImported, int imported, List<Issue> issues) {
        public Report { issues = List.copyOf(issues); }
    }
    private record LegacyPost(Long id, String title, String content, String requestMapping, Long blogMenuId, LocalDateTime dateTime) {}
    private record Inspection(List<LegacyPost> rows, String fingerprint, List<LegacyPost> pending, int alreadyImported, List<Issue> issues) {
        Report report(int imported) { return new Report(fingerprint, rows.size(), pending.size(), alreadyImported, imported, issues); }
    }
}
