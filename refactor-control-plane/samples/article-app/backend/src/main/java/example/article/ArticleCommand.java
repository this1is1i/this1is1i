package example.article;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ArticleCommand(
        @NotBlank String title,
        @NotBlank String content,
        @NotNull ArticleStatus status,
        @jakarta.validation.constraints.Positive Long menuId,
        @jakarta.validation.constraints.Positive Long expectedVersion) {
}
