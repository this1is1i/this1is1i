package io.refactorcontrolplane.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitWorktreeManagerContractTest {

    @TempDir
    Path tempDir;

    @Test
    void createsThreeIsolatedTaskBranchesIdempotentlyWithoutMovingMain() throws Exception {
        Path repository = tempDir.resolve("repository");
        Files.createDirectories(repository);
        git(repository, "init", "-b", "main");
        git(repository, "config", "user.email", "control-plane@example.invalid");
        git(repository, "config", "user.name", "Control Plane Test");
        Files.writeString(repository.resolve("README.md"), "fixture\n", StandardCharsets.UTF_8);
        git(repository, "add", "README.md");
        git(repository, "commit", "-m", "fixture");
        String baseCommit = git(repository, "rev-parse", "HEAD");

        GitWorktreeManager manager = new GitWorktreeManager();
        Path worktreeRoot = tempDir.resolve("executions");
        List<TaskWorktree> first = manager.createExecutions(
                repository, baseCommit, List.of("WP-120", "WP-130", "WP-140"), worktreeRoot);
        List<TaskWorktree> retry = manager.createExecutions(
                repository, baseCommit, List.of("WP-120", "WP-130", "WP-140"), worktreeRoot);

        assertEquals(3, first.size());
        assertEquals(first, retry);
        assertEquals(3, first.stream().map(TaskWorktree::branch).distinct().count());
        assertTrue(first.stream().allMatch(task -> Files.isDirectory(task.path())));
        assertTrue(first.stream().allMatch(task -> task.baseCommit().equals(baseCommit)));
        assertEquals(baseCommit, git(repository, "rev-parse", "main"));
        assertEquals("main", git(repository, "branch", "--show-current"));

        Files.writeString(repository.resolve("SECOND.md"), "second\n", StandardCharsets.UTF_8);
        git(repository, "add", "SECOND.md");
        git(repository, "commit", "-m", "second fixture");
        String differentBase = git(repository, "rev-parse", "HEAD");
        assertThrows(IllegalStateException.class, () -> manager.createExecutions(
                repository, differentBase, List.of("WP-120"), worktreeRoot));
    }

    @Test
    void taskIdsThatNormalizeToTheSameSlugStillReceiveDifferentWorktrees() throws Exception {
        Path repository = tempDir.resolve("collision-repository");
        Files.createDirectories(repository);
        git(repository, "init", "-b", "main");
        git(repository, "config", "user.email", "control-plane@example.invalid");
        git(repository, "config", "user.name", "Control Plane Test");
        Files.writeString(repository.resolve("README.md"), "fixture\n", StandardCharsets.UTF_8);
        git(repository, "add", "README.md");
        git(repository, "commit", "-m", "fixture");
        String baseCommit = git(repository, "rev-parse", "HEAD");

        List<TaskWorktree> worktrees = new GitWorktreeManager().createExecutions(
                repository, baseCommit, List.of("WP/A", "WP-A"), tempDir.resolve("collision-executions"));

        assertNotEquals(worktrees.get(0).branch(), worktrees.get(1).branch());
        assertNotEquals(worktrees.get(0).path(), worktrees.get(1).path());
    }

    @Test
    void repairsMissingBaseMetadataOnlyWhenTheTaskBranchStillEqualsTheRequestedBase() throws Exception {
        Path repository = tempDir.resolve("recovery-repository");
        Files.createDirectories(repository);
        git(repository, "init", "-b", "main");
        git(repository, "config", "user.email", "control-plane@example.invalid");
        git(repository, "config", "user.name", "Control Plane Test");
        Files.writeString(repository.resolve("README.md"), "fixture\n", StandardCharsets.UTF_8);
        git(repository, "add", "README.md");
        git(repository, "commit", "-m", "fixture");
        String baseCommit = git(repository, "rev-parse", "HEAD");
        Path worktreeRoot = tempDir.resolve("recovery-executions");
        GitWorktreeManager manager = new GitWorktreeManager();
        TaskWorktree created = manager.createExecutions(
                repository, baseCommit, List.of("WP-120"), worktreeRoot).getFirst();

        git(repository, "config", "--local", "--unset", "branch." + created.branch() + ".refactor-base");

        TaskWorktree recovered = manager.createExecutions(
                repository, baseCommit, List.of("WP-120"), worktreeRoot).getFirst();
        assertEquals(created, recovered);
        assertEquals(baseCommit, git(repository, "config", "--local", "--get",
                "branch." + created.branch() + ".refactor-base"));

        Files.writeString(created.path().resolve("TASK.md"), "work\n", StandardCharsets.UTF_8);
        git(created.path(), "add", "TASK.md");
        git(created.path(), "commit", "-m", "task work");
        git(repository, "config", "--local", "--unset", "branch." + created.branch() + ".refactor-base");
        assertThrows(IllegalStateException.class, () -> manager.createExecutions(
                repository, baseCommit, List.of("WP-120"), worktreeRoot));
    }

    private static String git(Path directory, String... arguments) throws Exception {
        String[] command = new String[arguments.length + 1];
        command[0] = "git";
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        Process process = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertEquals(0, process.waitFor(), output);
        return output;
    }
}
