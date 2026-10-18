package io.refactorcontrolplane.integration;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IntegrationGitRecoveryTest {
    @TempDir Path directory;
    Path repo() throws Exception {
        Path repo = Files.createDirectory(directory.resolve("repo"));
        IntegrationWorkflowTest.git(repo, "init", "-b", "main");
        return repo;
    }
    @Test void validationWorktreeCanReadLongLegacyPathsInItsIndependentObjectRepository() throws Exception {
        Path repo = repo();
        String relative = "src/MaskFormatAnnotationFormatterFactory.java";
        Path validationRoot = directory.resolve(".refactor-integrations").resolve(UUID.randomUUID().toString())
                .resolve("validation-" + UUID.randomUUID());
        while (validationRoot.resolve(relative).toString().length() <= 260) relative = "configuration/" + relative;
        Path file = repo.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "legacy source\n", StandardCharsets.UTF_8);
        IntegrationWorkflowTest.commit(repo);
        String commit = IntegrationWorkflowTest.git(repo, "rev-parse", "HEAD");
        var git = new IntegrationGit(repo);
        var plan = new IntegrationService.Plan(UUID.randomUUID().toString(), "now", "long-path", repo.toString(),
                "refs/heads/main", commit, java.util.List.of(), null, null);
        Path tree = git.validationTree(plan, commit, UUID.randomUUID().toString());
        assertTrue(tree.resolve(relative).toString().length() > 260);
        assertEquals("legacy source\n", Files.readString(tree.resolve(relative), StandardCharsets.UTF_8));
        assertTrue(git.clean(tree, commit));
        assertEquals(commit, IntegrationWorkflowTest.git(repo, "rev-parse", "main"));
    }
    @Test void interruptionBeforePublishingOwnedDirectoryCanRetry() throws Exception {
        Path repo = repo(); String id = UUID.randomUUID().toString();
        var interrupted = new IntegrationGit(repo) {
            @Override void publishDirectory(Path source, Path target) throws IOException { throw new IOException("before directory publication"); }
        };
        assertThrows(IllegalStateException.class, () -> interrupted.initialize(id, "fingerprint"));
        assertFalse(Files.exists(interrupted.directory(id)));
        var retry = new IntegrationGit(repo); retry.initialize(id, "fingerprint");
        assertEquals("fingerprint", Files.readString(retry.directory(id).resolve("owner"), StandardCharsets.UTF_8));
        assertTrue(Files.exists(retry.bare(id).resolve("HEAD")));
    }
    @Test void interruptionAfterPublishingOwnedDirectoryCanRetry() throws Exception {
        Path repo = repo(); String id = UUID.randomUUID().toString();
        var interrupted = new IntegrationGit(repo) {
            @Override void publishDirectory(Path source, Path target) throws IOException {
                super.publishDirectory(source, target); throw new IOException("after directory publication");
            }
        };
        assertThrows(IllegalStateException.class, () -> interrupted.initialize(id, "fingerprint"));
        assertEquals("fingerprint", Files.readString(interrupted.directory(id).resolve("owner"), StandardCharsets.UTF_8));
        new IntegrationGit(repo).initialize(id, "fingerprint");
        assertTrue(Files.exists(interrupted.bare(id).resolve("HEAD")));
    }
    @Test void racingForeignDirectoryIsPreservedByteForByte() throws Exception {
        Path repo = repo(); String id = UUID.randomUUID().toString();
        var racing = new IntegrationGit(repo) {
            @Override void publishDirectory(Path source, Path target) throws IOException {
                Files.createDirectory(target);
                Files.writeString(target.resolve("owner"), "foreign", StandardCharsets.UTF_8);
                super.publishDirectory(source, target);
            }
        };
        assertThrows(IllegalStateException.class, () -> racing.initialize(id, "fingerprint"));
        assertThrows(IllegalStateException.class, () -> new IntegrationGit(repo).initialize(id, "fingerprint"));
        assertEquals("foreign", Files.readString(racing.directory(id).resolve("owner"), StandardCharsets.UTF_8));
        assertFalse(Files.exists(racing.bare(id)));
    }
}
