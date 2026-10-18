package io.refactorcontrolplane.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.refactorcontrolplane.executor.ExecutionProtocol.*;
import io.refactorcontrolplane.integration.ControlledProcess;
import io.refactorcontrolplane.ownership.RepositoryPathPolicy;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Deterministic real file/validation/Git fixture, never a model or arbitrary code generator. */
public final class MockTaskExecutor implements TaskExecutor, AutoCloseable {
    private final ExecutorSettings.Definition settings;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    public MockTaskExecutor(ExecutorSettings.Definition settings, JdbcTemplate jdbc, ObjectMapper mapper) {
        this.settings = settings; this.jdbc = jdbc; this.mapper = mapper;
    }
    @Override public Result start(Request request) {
        Result running = new Result(request.execution().executionId(), request.requestHash(), 1, "RUNNING", null, List.of());
        try {
            jdbc.update("INSERT INTO mock_executor_job(execution_id,request_hash,result_json) VALUES(?,?,?)",
                    running.executionId(), running.requestHash(), json(running));
        } catch (org.springframework.dao.DuplicateKeyException ex) { return query(request); }
        workers.submit(() -> execute(request));
        return running;
    }
    @Override public Result query(Request request) {
        return jdbc.query("SELECT request_hash,result_json FROM mock_executor_job WHERE execution_id=?", (rs, n) -> {
            if (!request.requestHash().equals(rs.getString(1))) throw new IllegalStateException("Mock execution binding changed");
            try { return mapper.readValue(rs.getString(2), Result.class); }
            catch (java.io.IOException ex) { throw new IllegalStateException("Cannot read mock result"); }
        }, request.execution().executionId()).stream().findFirst().orElse(null);
    }
    private void execute(Request request) {
        var checks = new ArrayList<CheckResult>(); String sha = null; String state = "FAILED";
        try {
            Path root = Path.of(request.execution().path()).toRealPath();
            var paths = RepositoryPathPolicy.caseInsensitive();
            for (String name : settings.outputs().keySet()) {
                if (!name.equals(paths.normalize(name)) || name.contains(":") || name.contains("\\")
                        || request.allowedPaths().stream().noneMatch(allowed -> paths.covers(allowed, name))
                        || request.forbiddenPaths().stream().anyMatch(forbidden -> paths.covers(forbidden, name)))
                    throw new SecurityException("Mock output exceeds task authorization");
            }
            requireLive(request);
            if (!git(root, "branch", "--show-current").equals(request.execution().branch())
                    || !git(root, "rev-parse", "HEAD").equals(request.execution().baseCommit())
                    || !git(root, "status", "--porcelain").isBlank()) throw new IllegalStateException("Mock requires its clean initial worktree");
            for (var output : new TreeMap<>(settings.outputs()).entrySet()) {
                Path target = root.resolve(output.getKey()).normalize();
                Path parent = root;
                for (Path segment : root.relativize(target.getParent())) {
                    parent = parent.resolve(segment);
                    if (!Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(parent);
                    if (!parent.toRealPath().equals(parent)) throw new SecurityException("Mock output uses a filesystem alias");
                }
                requireLive(request);
                Files.writeString(target, output.getValue(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            }
            git(root, "add", "--all");
            git(root, "-c", "user.name=Control Plane Mock", "-c", "user.email=mock@localhost", "-c", "commit.gpgsign=false",
                    "-c", "core.hooksPath=", "commit", "-m", "Mock execution " + request.execution().executionId());
            sha = git(root, "rev-parse", "HEAD");
            for (var check : request.checks()) {
                requireLive(request);
                Path cwd = root.resolve(check.workingDirectory()).normalize().toRealPath();
                if (!cwd.startsWith(root)) throw new SecurityException("Validation directory escapes worktree");
                var result = ControlledProcess.run(cwd, check.argv(), environment(), new byte[0], check.timeoutSeconds(), 65536);
                checks.add(new CheckResult(check.id(), result.exitCode(), result.timedOut(), result.truncated()));
                if (!result.passed()) throw new IllegalStateException("Configured check failed");
            }
            if (!git(root, "rev-parse", "HEAD").equals(sha) || !git(root, "status", "--porcelain", "--untracked-files=no").isBlank())
                throw new IllegalStateException("Checks modified the validated commit");
            requireLive(request); state = "SUCCEEDED";
        } catch (RuntimeException | java.io.IOException ex) {
            // Keep output/worktree for inspection; no retry, rollback or unbounded exception logs.
        }
        Result result = new Result(request.execution().executionId(), request.requestHash(), 2, state, sha, checks);
        jdbc.update("UPDATE mock_executor_job SET result_json=? WHERE execution_id=? AND request_hash=?", json(result), result.executionId(), result.requestHash());
    }
    private static void requireLive(Request request) {
        if (Thread.currentThread().isInterrupted() || !Instant.now().isBefore(request.deadline()))
            throw new IllegalStateException("Execution deadline reached");
    }
    private static Map<String, String> environment() {
        return Map.of("GIT_CONFIG_NOSYSTEM", "1", "GIT_CONFIG_GLOBAL", System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null",
                "GIT_TERMINAL_PROMPT", "0");
    }
    private static String git(Path cwd, String... args) {
        var command = new ArrayList<String>(List.of("git", "-c", "core.longpaths=true")); command.addAll(List.of(args));
        var result = ControlledProcess.run(cwd, command, environment(), new byte[0], 30, 65536);
        if (!result.passed()) throw new IllegalStateException("Mock Git command failed");
        return result.out().strip();
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); } catch (java.io.IOException ex) { throw new IllegalStateException("Cannot persist mock job"); }
    }
    @Override public void close() {
        workers.shutdownNow();
        try { workers.awaitTermination(10, TimeUnit.SECONDS); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }
}
