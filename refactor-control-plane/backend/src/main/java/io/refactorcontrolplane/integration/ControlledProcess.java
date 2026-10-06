package io.refactorcontrolplane.integration;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/** Bounded local execution of trusted code. This is NOT an operating-system sandbox. */
final class ControlledProcess {
    record Result(int exitCode, boolean timedOut, byte[] stdout, byte[] stderr, boolean truncated) {
        String out() { return new String(stdout, StandardCharsets.UTF_8); }
        String err() { return new String(stderr, StandardCharsets.UTF_8); }
        boolean passed() { return exitCode == 0 && !timedOut && !truncated; }
    }
    private record Output(byte[] bytes, boolean truncated) {}

    static Result run(Path cwd, List<String> argv, Map<String, String> extra, byte[] input, int seconds, int limit) {
        Process process = null;
        Set<ProcessHandle> observedChildren = ConcurrentHashMap.newKeySet();
        Future<?> monitor = null;
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var builder = new ProcessBuilder(argv).directory(cwd.toFile());
            var environment = builder.environment();
            var inherited = new HashMap<>(environment);
            environment.clear();
            inherited.forEach((key, value) -> {
                if (Set.of("PATH", "SYSTEMROOT", "WINDIR", "TEMP", "TMP", "JAVA_HOME", "PATHEXT")
                        .contains(key.toUpperCase(Locale.ROOT))) environment.put(key, value);
            });
            environment.putAll(extra);
            process = builder.start();
            Process active = process;
            monitor = executor.submit(() -> {
                try {
                    while (active.isAlive()) {
                        active.descendants().forEach(observedChildren::add);
                        Thread.sleep(20);
                    }
                } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            });
            Future<Output> out = executor.submit(() -> drain(active.getInputStream(), limit));
            Future<Output> err = executor.submit(() -> drain(active.getErrorStream(), limit));
            Future<?> writer = executor.submit(() -> {
                try (var stream = active.getOutputStream()) { stream.write(input); }
                catch (IOException ex) { throw new UncheckedIOException(ex); }
            });
            boolean timeout = !process.waitFor(seconds, TimeUnit.SECONDS);
            if (timeout) kill(process);
            if (!timeout && observedChildren.stream().anyMatch(ProcessHandle::isAlive))
                throw new IllegalStateException("Configured command left a running child process");
            Output stdout, stderr;
            try {
                stdout = out.get(5, TimeUnit.SECONDS); stderr = err.get(5, TimeUnit.SECONDS);
                if (!timeout) writer.get(5, TimeUnit.SECONDS);
            } catch (TimeoutException ex) {
                kill(process); active.getInputStream().close(); active.getErrorStream().close();
                out.cancel(true); err.cancel(true); writer.cancel(true);
                throw new IllegalStateException("Child process retained its output pipes", ex);
            }
            return new Result(timeout ? -1 : process.exitValue(), timeout, stdout.bytes(), stderr.bytes(),
                    stdout.truncated() || stderr.truncated());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("Local execution interrupted", ex);
        } catch (IOException | ExecutionException ex) {
            throw new IllegalStateException("Cannot execute configured process", ex);
        } finally {
            // Cleanup must precede executor.close(), which waits for pipe-draining tasks.
            if (process != null) {
                kill(process);
                observedChildren.forEach(child -> { if (child.isAlive()) child.destroyForcibly(); });
                try { process.getOutputStream().close(); } catch (IOException ignored) {}
                try { process.getInputStream().close(); } catch (IOException ignored) {}
                try { process.getErrorStream().close(); } catch (IOException ignored) {}
            }
            if (monitor != null) monitor.cancel(true);
            executor.shutdownNow();
            executor.close();
        }
    }
    private static Output drain(InputStream input, int limit) throws IOException {
        var output = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; boolean truncated = false;
        try (input) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                int retained = Math.min(count, limit - output.size());
                output.write(buffer, 0, retained); truncated |= retained < count;
            }
        }
        return new Output(output.toByteArray(), truncated);
    }
    private static void kill(Process process) {
        var children = process.descendants().toList();
        children.reversed().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }
}
