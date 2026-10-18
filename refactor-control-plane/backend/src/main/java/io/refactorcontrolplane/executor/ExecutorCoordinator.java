package io.refactorcontrolplane.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.refactorcontrolplane.executor.ExecutionProtocol.*;
import io.refactorcontrolplane.governance.LocalIdentityRegistry.Principal;
import io.refactorcontrolplane.mcp.*;
import io.refactorcontrolplane.project.ProjectSource;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Service;

@Service
@EnableScheduling
public class ExecutorCoordinator {
    private final ProjectSource source;
    private final McpProjectRuntime runtime;
    private final ProjectMcpController journal;
    private final ObjectMapper mapper;
    private final ExecutorSettings settings;
    private final Map<String, TaskExecutor> adapters = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;
    private final boolean autoPoll;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor();
    public ExecutorCoordinator(ProjectSource source, McpProjectRuntime runtime, ProjectMcpController journal,
            ExecutorSettings settings, ObjectMapper mapper, JdbcTemplate jdbc,
            @Value("${control-plane.executors.auto-poll:true}") boolean autoPoll) {
        this.source = source; this.runtime = runtime; this.journal = journal; this.settings = settings;
        this.mapper = mapper; this.jdbc = jdbc; this.autoPoll = autoPoll;
    }
    public void authorize(Principal actor) {
        if (!source.overview().projectId().equals(actor.projectId())) throw new SecurityException("Project membership required");
    }
    public Run read(Principal actor, String id) {
        authorize(actor); var run = runtime.executorRun(id);
        if (run == null) throw new IllegalArgumentException("Unknown executor run"); return run;
    }
    public List<Map<String, String>> available(Principal actor) { authorize(actor); return settings.available(); }
    public Run dispatch(Principal actor, String id, String taskId, String executorId, long version, String key) {
        Run run;
        synchronized (runtime) {
            authorize(actor); source.requireExecutable();
            runtime.requireAssignedSubject(taskId, actor.subjectId());
            var existing = runtime.executorRun(id);
            if (existing != null) {
                if (!existing.request().execution().taskId().equals(taskId) || !existing.request().executorId().equals(executorId)
                        || !existing.request().owner().equals(actor.subjectId()) || existing.request().contextVersion() != version)
                    throw new IllegalStateException("Dispatch binding changed");
                return existing; // Never PUT again, even after a lost acknowledgement or process restart.
            }
            if (version != runtime.contextVersion()) throw new IllegalStateException("Context version changed");
            var execution = (McpProjectRuntime.Execution) runtime.task(taskId).get("execution");
            if (execution == null || !execution.executionId().equals(id)) throw new IllegalStateException("Current execution ID required");
            var config = settings.get(executorId);
            var task = source.overview().plan().workPackages().stream().filter(value -> value.id().equals(taskId)).findFirst().orElseThrow();
            var checks = source.executionChecks(taskId);
            int timeout = Math.max(300, checks.stream().mapToInt(check -> check.timeoutSeconds()).sum() + 120);
            var request = new Request("", source.planScope(), version, runtime.requireAssignedSubject(taskId, actor.subjectId()),
                    actor.subjectId(), executorId, settings.fingerprint(config), execution, task.allowedPaths(), source.forbiddenPaths(), checks, Instant.now().plusSeconds(timeout));
            try { request = request.withHash(ExecutorSettings.hash(mapper.writeValueAsString(request))); }
            catch (java.io.IOException ex) { throw new IllegalStateException("Cannot bind executor request"); }
            var args = envelope(taskId, version, key).put("taskExecutionId", id);
            args.set("request", mapper.valueToTree(request));
            runtime.beginExecutorDelivery(id);
            try { run = mapper.convertValue(journal.executorCommand(actor, "prepare", args), Run.class); }
            catch (RuntimeException | Error ex) { runtime.endExecutorDelivery(id); throw ex; }
        }
        // Remote I/O must not hold the runtime lock or prevent another task from executing.
        try {
            Result result = adapter(run.request()).start(run.request());
            return result == null ? unknown(actor, run) : observe(actor, run, result);
        } finally { runtime.endExecutorDelivery(id); }
    }
    public Run refresh(Principal actor, String id) {
        var run = read(actor, id);
        if (!actor.subjectId().equals(run.request().owner())) throw new SecurityException("Only the execution owner can reconcile this run");
        if (run.terminal()) return run;
        Result result = adapter(run.request()).query(run.request());
        return result == null ? unknown(actor, run) : observe(actor, run, result);
    }
    private Run unknown(Principal actor, Run original) {
        synchronized (runtime) {
            String id = original.request().execution().executionId();
            var current = runtime.executorRun(id);
            if (current.terminal() || current.state().equals("UNKNOWN")) return current;
            var args = envelope(original.request().execution().taskId(), original.request().contextVersion(), UUID.randomUUID().toString()).put("taskExecutionId", id);
            return mapper.convertValue(journal.executorCommand(actor, "unknown", args), Run.class);
        }
    }
    private Run observe(Principal actor, Run original, Result result) {
        synchronized (runtime) {
            var current = runtime.executorRun(original.request().execution().executionId());
            if (current.terminal()) return current; // A slow query cannot replace a terminal result or a stop confirmation.
            var args = envelope(original.request().execution().taskId(), original.request().contextVersion(), UUID.randomUUID().toString())
                    .put("taskExecutionId", original.request().execution().executionId());
            args.set("result", mapper.valueToTree(result));
            return mapper.convertValue(journal.executorCommand(actor, "observe", args), Run.class);
        }
    }
    private ObjectNode envelope(String task, long version, String key) {
        return mapper.createObjectNode().put("taskId", task).put("expectedVersion", version).put("idempotencyKey", key);
    }
    private TaskExecutor adapter(Request request) {
        var config = settings.get(request.executorId());
        if (!settings.fingerprint(config).equals(request.executorFingerprint()))
            throw new IllegalStateException("Executor configuration changed; restore its original binding before querying");
        return adapters.computeIfAbsent(config.id(), id -> config.kind().equals("MOCK")
                ? new MockTaskExecutor(config, jdbc, mapper) : new HttpTaskExecutor(config, mapper));
    }
    @Scheduled(fixedDelayString="${control-plane.executors.poll-ms:5000}")
    public void poll() {
        if (!autoPoll) return;
        for (Run run : runtime.activeExecutorRuns()) {
            String id = run.request().execution().executionId();
            if (!inFlight.add(id)) continue;
            readers.submit(() -> {
                try { refresh(new Principal(run.request().owner(), source.overview().projectId(), Set.of()), id); }
                catch (RuntimeException ex) { /* Preserve unknown state; explicit refresh exposes the actionable error. */ }
                finally { inFlight.remove(id); }
            });
        }
    }
    @jakarta.annotation.PreDestroy public void close() {
        readers.shutdownNow();
        for (TaskExecutor adapter : adapters.values()) if (adapter instanceof MockTaskExecutor mock) mock.close();
    }
}
