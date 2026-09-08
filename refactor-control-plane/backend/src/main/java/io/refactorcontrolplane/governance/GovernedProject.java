package io.refactorcontrolplane.governance;

import io.refactorcontrolplane.ownership.RepositoryPathPolicy;
import io.refactorcontrolplane.workpackage.WorkPackageStatus;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class GovernedProject {

    private final String projectId;
    private final Clock clock;
    private final RepositoryPathPolicy pathPolicy;
    private final Map<String, TaskPackage> tasks = new LinkedHashMap<>();
    private final List<ChangeEvent> events = new ArrayList<>();
    private final Set<String> processedKeys = new LinkedHashSet<>();
    private final Map<String, SubmissionResult> submissions = new LinkedHashMap<>();
    private final Map<String, EnumMap<QualityGate, Boolean>> gates = new LinkedHashMap<>();
    private long contextVersion = 1;

    public GovernedProject(String projectId, Clock clock, RepositoryPathPolicy pathPolicy) {
        this.projectId = Objects.requireNonNull(projectId);
        this.clock = Objects.requireNonNull(clock);
        this.pathPolicy = Objects.requireNonNull(pathPolicy);
    }

    public synchronized void registerTask(TaskPackage task) {
        if (tasks.putIfAbsent(task.id(), task) != null) {
            throw new IllegalArgumentException("Duplicate task: " + task.id());
        }
        append("task:" + task.id(), "TaskCreated", task.id(), Map.of("status", task.status().name()));
    }

    public synchronized TaskPackage task(String taskId) {
        TaskPackage task = tasks.get(taskId);
        if (task == null) throw new IllegalArgumentException("Unknown task: " + taskId);
        return task;
    }

    public synchronized void publishSemanticChange(
            SemanticAsset asset, long version, Set<String> affectedTaskIds, String idempotencyKey) {
        if (processedKeys.contains(idempotencyKey)) return;
        processedKeys.add(idempotencyKey);
        contextVersion++;
        appendUnchecked(idempotencyKey, asset.eventType(), projectId,
                Map.of("version", Long.toString(version)));
        for (String taskId : affectedTaskIds) {
            TaskPackage task = task(taskId);
            if (task.status().canTransitionTo(WorkPackageStatus.STALE)) {
                task.moveTo(WorkPackageStatus.STALE);
                gates.remove(taskId);
                appendUnchecked(idempotencyKey + ":stale:" + taskId, "TaskInvalidated", taskId,
                        Map.of("asset", asset.name(), "version", Long.toString(version)));
            }
        }
    }

    public synchronized SubmissionResult submitChangeset(
            String taskId, List<String> changedPaths, String idempotencyKey) {
        SubmissionResult previous = submissions.get(idempotencyKey);
        if (previous != null) return previous;
        TaskPackage task = task(taskId);
        List<String> unauthorized = changedPaths.stream()
                .map(pathPolicy::normalize)
                .filter(path -> task.forbiddenPaths().stream().anyMatch(parent -> pathPolicy.covers(parent, path))
                        || task.allowedPaths().stream().noneMatch(parent -> pathPolicy.covers(parent, path)))
                .toList();
        SubmissionResult result = new SubmissionResult(unauthorized.isEmpty(), unauthorized);
        submissions.put(idempotencyKey, result);
        if (result.accepted()) gates.remove(taskId);
        append(idempotencyKey, result.accepted() ? "CommitSubmitted" : "ChangesetRejected", taskId,
                Map.of("unauthorizedPaths", String.join(",", unauthorized)));
        return result;
    }

    public synchronized void recordGate(
            String taskId, QualityGate gate, boolean passed, ProjectRole role, String idempotencyKey) {
        if (role != gate.approvingRole()) throw new UnauthorizedApprovalException(gate, role);
        TaskPackage task = task(taskId);
        WorkPackageStatus requiredStatus = gate == QualityGate.CODE_REVIEW
                ? WorkPackageStatus.CODE_REVIEW : WorkPackageStatus.TESTING;
        if (task.status() != requiredStatus) {
            throw new IllegalStateException(
                    "Gate " + gate + " requires task status " + requiredStatus + ", actual " + task.status());
        }
        if (processedKeys.contains(idempotencyKey)) return;
        gates.computeIfAbsent(taskId, ignored -> new EnumMap<>(QualityGate.class)).put(gate, passed);
        append(idempotencyKey, passed ? "GatePassed" : "GateFailed", taskId,
                Map.of("gate", gate.name(), "role", role.name()));
    }

    public synchronized void acceptTask(String taskId) {
        TaskPackage task = task(taskId);
        Map<QualityGate, Boolean> taskGates = gates.getOrDefault(taskId, new EnumMap<>(QualityGate.class));
        boolean allPassed = Set.of(QualityGate.values()).stream()
                .allMatch(gate -> Boolean.TRUE.equals(taskGates.get(gate)));
        if (!allPassed) throw new GateNotSatisfiedException(taskId);
        task.moveTo(WorkPackageStatus.ACCEPTED);
        append("accepted:" + taskId, "TaskAccepted", taskId, Map.of());
    }

    public synchronized ManualMergeProposal createManualMergeProposal(
            String taskId, String branch, String commitSha) {
        if (task(taskId).status() != WorkPackageStatus.ACCEPTED) {
            throw new GateNotSatisfiedException(taskId);
        }
        append("merge-proposal:" + taskId + ":" + commitSha, "ManualMergeProposed", taskId,
                Map.of("branch", branch, "commit", commitSha));
        return new ManualMergeProposal(taskId, branch, commitSha, true, false);
    }

    public synchronized AuditReport auditReport() {
        Set<String> types = events.stream().map(ChangeEvent::type)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> risks = new LinkedHashSet<>();
        if (types.contains("ChangesetRejected")) risks.add("Unauthorized path modification");
        if (types.contains("TaskInvalidated")) risks.add("Semantic dependency invalidation");
        return new AuditReport(projectId, new ArrayList<>(tasks.values()), types, events, risks);
    }

    public synchronized long contextVersion() { return contextVersion; }
    public synchronized List<ChangeEvent> events() { return List.copyOf(events); }

    private void append(String key, String type, String subject, Map<String, String> details) {
        if (!processedKeys.add(key)) return;
        appendUnchecked(key, type, subject, details);
    }

    private void appendUnchecked(String key, String type, String subject, Map<String, String> details) {
        events.add(new ChangeEvent(events.size() + 1L, key, type, subject, clock.instant(), details));
    }
}
