package io.refactorcontrolplane.git;

import java.nio.file.Path;

public record TaskWorktree(
        String taskId,
        String executionId,
        String branch,
        Path path,
        String baseCommit) {
}
