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

    public synchronized TaskWorktree createExecution(Path repository, String baseCommit, String taskId,
            String executionId, Path root) {
        if (!executionId.matches("[a-zA-Z0-9-]{1,100}")) throw new IllegalArgumentException("Invalid execution ID");
        TaskWorktree created = createExecutions(repository, baseCommit, List.of(taskId + "-" + executionId), root).getFirst();
        return new TaskWorktree(taskId, executionId, created.branch(), created.path(), created.baseCommit());
    }

    public synchronized List<String> verifiedChangedPaths(Path repository, TaskWorktree execution, String commitSha) {
        if (commitSha == null || !commitSha.matches("[0-9a-fA-F]{40}")) {
            throw new IllegalArgumentException("A full commit SHA is required");
        }
        requireRepositoryIdentity(repository, execution.path());
        String branch = git(execution.path(), "branch", "--show-current").output();
        if (!execution.branch().equals(branch)) throw new IllegalStateException("Execution branch changed");
        String head = git(repository, "rev-parse", "refs/heads/" + execution.branch()).output();
        if (!head.equalsIgnoreCase(commitSha)) throw new IllegalStateException("Submission must equal the execution branch HEAD");
        if (gitAllowFailure(repository, "merge-base", "--is-ancestor", execution.baseCommit(), head).exitCode() != 0) {
            throw new IllegalStateException("Execution commit does not descend from its bound base");
        }
        CommandResult diff = gitRaw(repository, "diff", "--no-ext-diff", "--no-renames", "--name-only", "-z",
                execution.baseCommit(), head, "--");
        if (diff.exitCode() != 0) throw new IllegalStateException("Cannot inspect execution diff");
        return java.util.Arrays.stream(diff.output().split("\u0000")).filter(value -> !value.isEmpty()).toList();
    }

    private static void requireRepositoryIdentity(Path source, Path worktree) {
        try {
            Path expected = Path.of(git(source, "rev-parse", "--path-format=absolute", "--git-common-dir").output()).toRealPath();
            Path actual = Path.of(git(worktree, "rev-parse", "--path-format=absolute", "--git-common-dir").output()).toRealPath();
            if (!expected.equals(actual)) throw new IllegalStateException("Worktree belongs to another repository");
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot verify worktree repository identity", exception);
        }
    }

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
            requireRepositoryIdentity(repo, path);
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
        CommandResult result = gitRaw(directory, arguments);
        return new CommandResult(result.exitCode(), result.output().trim());
    }

    private static CommandResult gitRaw(Path directory, String... arguments) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true);
        Process process = null;
        try {
            process = builder.start();
            Process running = process;
            var outputTask = new java.util.concurrent.FutureTask<byte[]>(() -> running.getInputStream().readAllBytes());
            Thread.ofVirtual().start(outputTask);
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Git command timed out: " + String.join(" ", command));
            }
            String output = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(outputTask.get(5, TimeUnit.SECONDS))).toString();
            return new CommandResult(process.exitValue(), output);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot execute Git", exception);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException exception) {
            throw new IllegalStateException("Cannot drain Git command output", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while executing Git", exception);
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                try { process.waitFor(5, TimeUnit.SECONDS); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            }
        }
    }

    private record CommandResult(int exitCode, String output) {
    }
}
