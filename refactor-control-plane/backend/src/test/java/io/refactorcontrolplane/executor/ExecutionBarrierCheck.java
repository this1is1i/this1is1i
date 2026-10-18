package io.refactorcontrolplane.executor;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;

/** An actual configured check process; marker files let the parent prove overlap without timing guesses. */
public final class ExecutionBarrierCheck {
    public static void main(String[] args) throws Exception {
        Path barrier = Path.of(args[0]); String task = args[1];
        if (!Files.readString(Path.of("result.txt"), StandardCharsets.UTF_8).equals(task))
            throw new IllegalStateException("Wrong worktree output");
        Path marker = barrier.resolve(task + ".entered.tmp");
        Files.writeString(marker, Instant.now() + "\n" + ProcessHandle.current().pid(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Files.move(marker, barrier.resolve(task + ".entered"), StandardCopyOption.ATOMIC_MOVE);
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(60).toNanos();
        while (!Files.exists(barrier.resolve(task + ".release"))) {
            if (System.nanoTime() >= deadline) throw new IllegalStateException("Barrier release timed out");
            Thread.sleep(50);
        }
        Files.writeString(barrier.resolve(task + ".finished"), Instant.now().toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
    }
}
