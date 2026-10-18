package example.article;

import java.time.Instant;

public record Article(
        long id,
        String title,
        String content,
        ArticleStatus status,
        Instant updatedAt, long menuId, long version, String publicPath) {
}
