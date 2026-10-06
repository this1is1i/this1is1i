package io.refactorcontrolplane.article;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ArticleCommand(
        @NotBlank @Size(max = 255) String title,
        @NotBlank String content,
        @NotNull ArticleStatus status) {
}
