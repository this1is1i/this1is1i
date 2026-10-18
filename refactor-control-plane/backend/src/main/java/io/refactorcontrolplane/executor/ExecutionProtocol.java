package io.refactorcontrolplane.executor;

import io.refactorcontrolplane.integration.IntegrationProfiles.Check;
import io.refactorcontrolplane.mcp.McpProjectRuntime.Execution;
import java.time.Instant;
import java.util.*;

public final class ExecutionProtocol {
    private ExecutionProtocol() {}
    public record Request(String requestHash, String planScope, long contextVersion, long assignmentEpoch,
            String owner, String executorId, String executorFingerprint, Execution execution,
            Set<String> allowedPaths, Set<String> forbiddenPaths, List<Check> checks, Instant deadline) {
        public Request {
            allowedPaths = Collections.unmodifiableSet(new TreeSet<>(allowedPaths));
            forbiddenPaths = Collections.unmodifiableSet(new TreeSet<>(forbiddenPaths));
            checks = List.copyOf(checks);
        }
        public Request withHash(String hash) { return new Request(hash, planScope, contextVersion, assignmentEpoch,
                owner, executorId, executorFingerprint, execution, allowedPaths, forbiddenPaths, checks, deadline); }
    }
    public record CheckResult(String id, int exitCode, boolean timedOut, boolean truncated) {}
    public record Result(String executionId, String requestHash, long sequence, String state, String commitSha, List<CheckResult> checks,
            List<ExecutorModelRun> modelRuns) {
        public Result(String executionId, String requestHash, long sequence, String state, String commitSha, List<CheckResult> checks) {
            this(executionId, requestHash, sequence, state, commitSha, checks, null);
        }
        public Result {
            checks = checks == null ? List.of() : List.copyOf(checks);
            if (modelRuns != null) {
                modelRuns = List.copyOf(modelRuns);
                if (modelRuns.size() > 20 || modelRuns.stream().map(ExecutorModelRun::id).distinct().count() != modelRuns.size()
                        || (!modelRuns.isEmpty() && !"SUCCEEDED".equals(state)))
                    throw new IllegalArgumentException("Invalid model receipt list or execution state");
            }
        }
    }
    public record Run(Request request, String state, Result result, String observedAt, String modelEvidenceRef) {
        public Run(Request request, String state, Result result) { this(request, state, result, Instant.now().toString(), null); }
        public Run(Request request, String state, Result result, String observedAt) { this(request, state, result, observedAt, null); }
        public boolean terminal() { return Set.of("SUCCEEDED", "FAILED", "STOPPED").contains(state); }
    }
}
