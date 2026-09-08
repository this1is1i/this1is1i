package io.refactorcontrolplane.git;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class GitWorktreeManager {

    public synchronized List<TaskWorktree> createExecutions(
            Path repository,
            String baseCommit,
            List<String> taskIds,
            Path worktreeRoot) {
        Path repo = repository.toAbsolutePath().normalize();
        Path root = worktreeRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(repo.resolve(".git"))) {
            throw new IllegalArgumentException("Not a Git repository: " + repo);
        }
        if (root.startsWith(repo)) {
            throw new IllegalArgumentException("Worktrees must be outside the source repository: " + root);
        }
        String verifiedBase = git(repo, "rev-parse", "--verify", baseCommit + "^{commit}").output();
        if (!verifiedBase.matches("[0-9a-fA-F]{40}")) {
            throw new IllegalArgumentException("Base commit must resolve to a full commit SHA");
        }
        try {
            Files.createDirectories(root);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot create worktree root " + root, exception);
        }

        List<TaskWorktree> created = new ArrayList<>();
        for (String taskId : taskIds) {
            created.add(createExecution(repo, verifiedBase, taskId, root));
        }
        return List.copyOf(created);
    }

    private TaskWorktree createExecution(Path repo, String baseCommit, String taskId, Path root) {
        String slug = taskId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-");
        if (slug.isBlank()) throw new IllegalArgumentException("Invalid task ID: " + taskId);
        String stableId = slug + "-" + shortHash(taskId);
        String branch = "codex/task-" + stableId;
        String executionId = "exec-" + stableId;
        Path path = root.resolve(executionId).normalize();
        if (!path.getParent().equals(root)) throw new IllegalArgumentException("Invalid execution path");
        String baseConfigKey = "branch." + branch + ".refactor-base";

        if (Files.exists(path)) {
            String actualBranch = git(path, "branch", "--show-current").output();
            if (!branch.equals(actualBranch)) {
                throw new IllegalStateException("Existing worktree uses branch " + actualBranch + ", expected " + branch);
            }
            requireOrRepairRecordedBase(repo, baseConfigKey, baseCommit, branch);
            return new TaskWorktree(taskId, executionId, branch, path, baseCommit);
        }

        CommandResult branchProbe = gitAllowFailure(repo, "show-ref", "--verify", "--quiet", "refs/heads/" + branch);
        if (branchProbe.exitCode() == 0) {
            requireOrRepairRecordedBase(repo, baseConfigKey, baseCommit, branch);
            git(repo, "worktree", "add", path.toString(), branch);
        } else if (branchProbe.exitCode() == 1) {
            git(repo, "worktree", "add", "-b", branch, path.toString(), baseCommit);
            git(repo, "config", "--local", baseConfigKey, baseCommit);
        } else {
            throw new IllegalStateException("Cannot inspect task branch: " + branchProbe.output());
        }
        return new TaskWorktree(taskId, executionId, branch, path, baseCommit);
    }

    private static void requireOrRepairRecordedBase(
            Path repo, String configKey, String expectedBase, String branch) {
        CommandResult recorded = gitAllowFailure(repo, "config", "--local", "--get", configKey);
        if (recorded.exitCode() == 0) {
            if (recorded.output().equals(expectedBase)) return;
            throw new IllegalStateException(
                    "Existing task branch has a different original base commit: " + branch);
        }
        if (recorded.exitCode() != 1) {
            throw new IllegalStateException("Cannot inspect original base metadata for task branch: " + branch);
        }

        String branchHead = git(repo, "rev-parse", "--verify", "refs/heads/" + branch + "^{commit}").output();
        if (!branchHead.equals(expectedBase)) {
            throw new IllegalStateException(
                    "Existing task branch has no base metadata and no longer equals the requested base: " + branch);
        }
        git(repo, "config", "--local", configKey, expectedBase);
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static CommandResult git(Path directory, String... arguments) {
        CommandResult result = gitAllowFailure(directory, arguments);
        if (result.exitCode() != 0) {
            throw new IllegalStateException("Git command failed (" + result.exitCode() + "): " + result.output());
        }
        return result;
    }

    private static CommandResult gitAllowFailure(Path directory, String... arguments) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true);
        try {
            Process process = builder.start();
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Git command timed out: " + String.join(" ", command));
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return new CommandResult(process.exitValue(), output);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot execute Git", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while executing Git", exception);
        }
    }

    private record CommandResult(int exitCode, String output) {
    }
}
