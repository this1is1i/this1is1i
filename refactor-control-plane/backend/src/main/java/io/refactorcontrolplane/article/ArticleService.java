package io.refactorcontrolplane.article;

import java.time.Clock;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;

@Service
public class ArticleService {

    private final JdbcTemplate jdbc;
    private final Clock clock = Clock.systemUTC();

    public ArticleService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Article create(ArticleCommand command) {
        var updatedAt = clock.instant();
        var keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO modern_articles (title, content, status, updated_at)
                    VALUES (?, ?, ?, ?)
                    """, new String[] {"id"});
            statement.setString(1, command.title());
            statement.setString(2, command.content());
            statement.setString(3, command.status().name());
            statement.setTimestamp(4, Timestamp.from(updatedAt));
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) throw new IllegalStateException("Database did not return an article ID");
        return new Article(key.longValue(), command.title(), command.content(), command.status(), updatedAt);
    }

    public List<Article> findAll() {
        return jdbc.query("""
                SELECT id, title, content, status, updated_at
                  FROM modern_articles ORDER BY id
                """, (row, number) -> article(row));
    }

    public Article find(long id) {
        return jdbc.query("""
                SELECT id, title, content, status, updated_at
                  FROM modern_articles WHERE id = ?
                """, (row, number) -> article(row), id).stream()
                .findFirst().orElseThrow(() -> new ArticleNotFoundException(id));
    }

    public Article update(long id, ArticleCommand command) {
        find(id);
        var updatedAt = clock.instant();
        jdbc.update("""
                UPDATE modern_articles
                   SET title = ?, content = ?, status = ?, updated_at = ?
                 WHERE id = ?
                """, command.title(), command.content(), command.status().name(),
                Timestamp.from(updatedAt), id);
        return new Article(id, command.title(), command.content(), command.status(), updatedAt);
    }

    public void delete(long id) {
        if (jdbc.update("DELETE FROM modern_articles WHERE id = ?", id) != 1) {
            throw new ArticleNotFoundException(id);
        }
    }

    private static Article article(java.sql.ResultSet row) throws java.sql.SQLException {
        return new Article(
                row.getLong("id"),
                row.getString("title"),
                row.getString("content"),
                ArticleStatus.valueOf(row.getString("status")),
                row.getTimestamp("updated_at").toInstant());
    }
}
