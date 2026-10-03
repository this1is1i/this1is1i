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
    private final Set<String> invalidatedTasks = new LinkedHashSet<>();
    private final Map<SemanticAsset, Long> assetVersions = new EnumMap<>(SemanticAsset.class);
    private long contextVersion = 1;

    public GovernedProject(String projectId, Clock clock, RepositoryPathPolicy pathPolicy) {
        this.projectId = Objects.requireNonNull(projectId);
        this.clock = Objects.requireNonNull(clock);
        this.pathPolicy = Objects.requireNonNull(pathPolicy);
        for (SemanticAsset asset : SemanticAsset.values()) assetVersions.put(asset, 1L);
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
        applySemanticChange(asset, version, affectedTaskIds, idempotencyKey, false);
    }

    /** Restore historical facts without applying today's command validation to yesterday's records. */
    public synchronized void restoreLegacySemanticChange(
            SemanticAsset asset, long version, Set<String> affectedTaskIds, String idempotencyKey) {
        applySemanticChange(asset, version, affectedTaskIds, idempotencyKey, true);
    }

    private void applySemanticChange(SemanticAsset asset, long version, Set<String> affectedTaskIds,
            String idempotencyKey, boolean legacy) {
        if (processedKeys.contains(idempotencyKey)) return;
        Objects.requireNonNull(asset, "asset must not be null");
        if (!legacy && version <= assetVersions.get(asset)) throw new IllegalArgumentException("asset version must increase");
        List<TaskPackage> affectedTasks = affectedTaskIds.stream().map(this::task).toList();
        assetVersions.put(asset, Math.max(assetVersions.get(asset), version));
        processedKeys.add(idempotencyKey);
        contextVersion++;
        appendUnchecked(idempotencyKey, asset.eventType(), projectId,
                Map.of("version", Long.toString(version)));
        for (TaskPackage task : affectedTasks) {
            String taskId = task.id();
            if (task.status() == WorkPackageStatus.CANCELLED) continue;
            invalidatedTasks.add(taskId);
            gates.remove(taskId);
            if (task.status().canTransitionTo(WorkPackageStatus.STALE)) {
                task.moveTo(WorkPackageStatus.STALE);
            }
            appendUnchecked(idempotencyKey + ":stale:" + taskId, "TaskInvalidated", taskId,
                    Map.of("asset", asset.name(), "version", Long.toString(version)));
        }
    }

    public synchronized SubmissionResult submitChangeset(
            String taskId, List<String> changedPaths, String idempotencyKey) {
        SubmissionResult previous = submissions.get(idempotencyKey);
        if (previous != null) return previous;
        TaskPackage task = task(taskId);
        if (invalidatedTasks.contains(taskId)) {
            throw new IllegalStateException("Task requires semantic revalidation: " + taskId);
        }
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
        if (invalidatedTasks.contains(taskId)) {
            throw new IllegalStateException("Task requires semantic revalidation: " + taskId);
        }
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
        if (!allPassed || invalidatedTasks.contains(taskId)) throw new GateNotSatisfiedException(taskId);
        task.moveTo(WorkPackageStatus.ACCEPTED);
        append("accepted:" + taskId + ":" + contextVersion, "TaskAccepted", taskId, Map.of());
    }

    public synchronized ManualMergeProposal createManualMergeProposal(
            String taskId, String branch, String commitSha) {
        if (task(taskId).status() != WorkPackageStatus.ACCEPTED || invalidatedTasks.contains(taskId)) {
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
    public synchronized boolean currentlyValid(String taskId) {
        task(taskId);
        return !invalidatedTasks.contains(taskId);
    }

    public synchronized boolean gatePassed(String taskId, QualityGate gate) {
        return Boolean.TRUE.equals(gates.getOrDefault(taskId, new EnumMap<>(QualityGate.class)).get(gate));
    }

    public synchronized void revalidateTask(String taskId, String key) {
        TaskPackage old = task(taskId);
        if (!invalidatedTasks.contains(taskId) || old.status() == WorkPackageStatus.CANCELLED) {
            throw new IllegalStateException("Task does not require revalidation: " + taskId);
        }
        tasks.put(taskId, new TaskPackage(taskId, old.baseCommit(), contextVersion,
                assetVersions.get(SemanticAsset.API_CONTRACT), assetVersions.get(SemanticAsset.DB_SCHEMA),
                assetVersions.get(SemanticAsset.ARCHITECTURE_DECISION), old.allowedPaths(), old.forbiddenPaths(),
                old.workingDirectory(), old.acceptanceCommands(), WorkPackageStatus.READY));
        gates.remove(taskId);
        invalidatedTasks.remove(taskId);
        append(key, "TaskRevalidated", taskId, Map.of("contextVersion", Long.toString(contextVersion)));
    }

    public synchronized void recordEvidence(String key, String taskId, Map<String, String> details) {
        task(taskId);
        append(key, "GovernanceEvidenceRecorded", taskId, Map.copyOf(details));
    }

    public synchronized void markLegacyUnverified(String taskId) {
        task(taskId);
        gates.remove(taskId);
        invalidatedTasks.add(taskId);
        append("legacy-unverified:" + taskId, "LegacyExecutionUnverified", taskId, Map.of());
    }

    public synchronized State snapshot() {
        Map<String, Map<QualityGate, Boolean>> gateCopy = new LinkedHashMap<>();
        gates.forEach((key, value) -> gateCopy.put(key, Map.copyOf(value)));
        return new State(projectId, contextVersion, tasks.values().stream().map(TaskPackage::snapshot).toList(),
                List.copyOf(events), Set.copyOf(processedKeys), Map.copyOf(submissions), gateCopy,
                Set.copyOf(invalidatedTasks), Map.copyOf(assetVersions));
    }

    public synchronized void restore(State state) {
        if (!projectId.equals(state.projectId())) throw new IllegalArgumentException("Projection project mismatch");
        tasks.clear();
        state.tasks().forEach(value -> tasks.put(value.id(), TaskPackage.from(value)));
        contextVersion = state.contextVersion();
        events.clear(); events.addAll(state.events());
        processedKeys.clear(); processedKeys.addAll(state.processedKeys());
        submissions.clear(); submissions.putAll(state.submissions());
        gates.clear();
        state.gates().forEach((key, value) -> {
            var copy = new EnumMap<QualityGate, Boolean>(QualityGate.class);
            copy.putAll(value); gates.put(key, copy);
        });
        invalidatedTasks.clear(); invalidatedTasks.addAll(state.invalidatedTasks());
        if (state.assetVersions() != null) assetVersions.putAll(state.assetVersions());
    }

    public record State(String projectId, long contextVersion, List<TaskPackage.Snapshot> tasks,
            List<ChangeEvent> events, Set<String> processedKeys, Map<String, SubmissionResult> submissions,
            Map<String, Map<QualityGate, Boolean>> gates, Set<String> invalidatedTasks,
            Map<SemanticAsset, Long> assetVersions) {}

    private void append(String key, String type, String subject, Map<String, String> details) {
        if (!processedKeys.add(key)) return;
        appendUnchecked(key, type, subject, details);
    }

    private void appendUnchecked(String key, String type, String subject, Map<String, String> details) {
        events.add(new ChangeEvent(events.size() + 1L, key, type, subject, clock.instant(), details));
    }
}
