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
