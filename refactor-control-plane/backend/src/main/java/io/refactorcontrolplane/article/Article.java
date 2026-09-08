package io.refactorcontrolplane.article;

import java.time.Instant;

public record Article(
        long id,
        String title,
        String content,
        ArticleStatus status,
        Instant updatedAt) {
}
