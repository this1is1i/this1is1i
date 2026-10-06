package io.refactorcontrolplane.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.refactorcontrolplane.api.DemoProjectService;
import io.refactorcontrolplane.governance.GovernedProject;
import io.refactorcontrolplane.governance.ProjectRole;
import io.refactorcontrolplane.governance.QualityGate;
import io.refactorcontrolplane.governance.SemanticAsset;
import io.refactorcontrolplane.governance.SubmissionResult;
import io.refactorcontrolplane.governance.TaskPackage;
import io.refactorcontrolplane.ownership.LeaseRequest;
import io.refactorcontrolplane.ownership.LeaseReservation;
import io.refactorcontrolplane.ownership.OwnershipMode;
import io.refactorcontrolplane.ownership.PathLease;
import io.refactorcontrolplane.ownership.PathLeaseRegistry;
import io.refactorcontrolplane.ownership.RepositoryPathPolicy;
import io.refactorcontrolplane.ownership.LeaseNotFoundException;
import io.refactorcontrolplane.ownership.LeaseOwnerMismatchException;
import io.refactorcontrolplane.ownership.LeaseVersionConflictException;
import io.refactorcontrolplane.persistence.AuditEventRepository;
import io.refactorcontrolplane.persistence.PersistedAuditEvent;
import io.refactorcontrolplane.git.GitWorktreeManager;
import io.refactorcontrolplane.git.TaskWorktree;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import io.refactorcontrolplane.workpackage.WorkPackageStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class McpProjectRuntime {

    private final GovernedProject project;
    private PathLeaseRegistry leases;
    private final RepositoryPathPolicy pathPolicy;
    private final List<String> taskIds;
    private final byte[] tokenSecret = (UUID.randomUUID().toString() + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
    private final Map<String, Long> assignmentEpochs = new LinkedHashMap<>();
    private final Map<String, Set<String>> dependencies = new LinkedHashMap<>();
    private boolean reconciliationRequired;
    private final Path repository;
    private final GitWorktreeManager git = new GitWorktreeManager();
    private final Map<String, Execution> executions = new LinkedHashMap<>();
    private final Map<String, String> assignmentOwners = new LinkedHashMap<>();
    private java.util.function.Consumer<String> integrationMergeGuard = taskId -> {};

    public synchronized void integrationMergeGuard(java.util.function.Consumer<String> guard) {
        integrationMergeGuard = guard;
    }

    public record IntegrationInput(String taskId, String owner, Execution execution, long contextVersion,
            long contractVersion, long schemaVersion, long adrVersion, List<String> dependencies) {}

    /** Captures authoritative, submitted executions in deterministic dependency order. */
    public synchronized List<IntegrationInput> integrationInputs(List<String> requested) {
        if (reconciliationRequired) throw new IllegalStateException("Operation journal requires reconciliation");
        if (requested == null || requested.isEmpty() || requested.size() > 20
                || new java.util.HashSet<>(requested).size() != requested.size())
            throw new IllegalArgumentException("Supply 1 to 20 distinct task IDs");
        var pending = new java.util.TreeSet<>(requested);
        var ordered = new java.util.ArrayList<IntegrationInput>();
        while (!pending.isEmpty()) {
            String next = pending.stream().filter(id -> dependencies.getOrDefault(id, Set.of()).stream()
                    .noneMatch(pending::contains)).findFirst().orElseThrow(() -> new IllegalStateException("Cyclic task dependencies"));
            TaskPackage task = project.task(next);
            if (!project.currentlyValid(next) || !Set.of(WorkPackageStatus.SUBMITTED, WorkPackageStatus.CODE_REVIEW,
                    WorkPackageStatus.TESTING, WorkPackageStatus.ACCEPTED).contains(task.status()))
                throw new IllegalStateException("Integration requires a valid submitted task: " + next);
            for (String prerequisite : dependencies.getOrDefault(next, Set.of())) {
                if (!requested.contains(prerequisite))
                    throw new IllegalStateException("Include the complete dependency closure; missing prerequisite: " + prerequisite);
            }
            Execution execution = executions.get(next);
            if (execution == null || execution.commitSha() == null) throw new IllegalStateException("Missing submitted execution");
            git.verifiedChangedPaths(repository, execution.worktree(), execution.commitSha());
            ordered.add(new IntegrationInput(next, assignmentOwners.get(next), execution, task.contextVersion(),
                    task.contractVersion(), task.schemaVersion(), task.adrVersion(),
                    dependencies.getOrDefault(next, Set.of()).stream().sorted().toList()));
            pending.remove(next);
        }
        return List.copyOf(ordered);
    }

    public synchronized void requireIntegrationAccepted(List<String> ids) {
        integrationInputs(ids);
        for (String id : ids) if (project.task(id).status() != WorkPackageStatus.ACCEPTED)
            throw new IllegalStateException("Task has not completed human acceptance: " + id);
    }

    public McpProjectRuntime(
            DemoProjectService projectService, AuditEventRepository auditEvents, ObjectMapper mapper) {
        var overview = projectService.overview();
        this.repository = projectService.repository();
        this.pathPolicy = RepositoryPathPolicy.caseInsensitive();
        this.project = new GovernedProject(overview.projectId(), Clock.systemUTC(), pathPolicy);
        this.leases = new PathLeaseRegistry(Clock.systemUTC(), pathPolicy);
        this.taskIds = overview.plan().workPackages().stream().map(work -> work.id()).toList();
        this.taskIds.forEach(taskId -> assignmentEpochs.put(taskId, 0L));
        this.taskIds.forEach(taskId -> assignmentOwners.put(taskId, "task:" + taskId));
        overview.plan().workPackages().forEach(work -> dependencies.put(work.id(), work.dependencies()));
        overview.plan().workPackages().forEach(work -> project.registerTask(new TaskPackage(
                work.id(), overview.plan().baseCommit(), 1, 1, 1, 1,
                work.allowedPaths(), Set.of(".git", "backend/data"), work.workingDirectory(),
                work.acceptanceCommands(),
                WorkPackageStatus.READY)));
        recover(auditEvents, mapper);
    }

    public synchronized Object execute(String name, JsonNode arguments) {
        if (reconciliationRequired) throw new IllegalStateException("Operation journal requires restart reconciliation");
        String taskId = requiredText(arguments, "taskId");
        authorize(taskId, requiredText(arguments, "taskToken"));
        requireContextVersion(arguments.path("expectedVersion").asLong());
        return executeWrite(name, taskId, arguments);
    }

    public synchronized void validateToken(JsonNode arguments) {
        if (reconciliationRequired) throw new IllegalStateException("Operation journal requires restart reconciliation");
        String taskId = requiredText(arguments, "taskId");
        authorize(taskId, requiredText(arguments, "taskToken"));
    }

    private Object executeWrite(String name, String taskId, JsonNode arguments) {
        if (name.startsWith("operator:")) return operator(name.substring(9), taskId, arguments);
        return switch (name) {
            case "claim_task" -> claim(taskId, arguments);
            case "reserve_paths" -> reserve(taskId, arguments);
            case "heartbeat" -> heartbeat(taskId, arguments);
            case "release_paths" -> release(taskId, arguments);
            case "publish_contract_change" -> publish(taskId, SemanticAsset.API_CONTRACT, arguments);
            case "publish_schema_change" -> publish(taskId, SemanticAsset.DB_SCHEMA, arguments);
            case "record_architecture_decision" -> publish(
                    taskId, SemanticAsset.ARCHITECTURE_DECISION, arguments);
            case "submit_changeset" -> submit(taskId, arguments);
            case "report_blocker" -> block(taskId);
            case "report_change_event" -> reportEvent(taskId, arguments);
            default -> throw new IllegalArgumentException("Unsupported write tool: " + name);
        };
    }

    public synchronized List<Map<String, Object>> readyTasks() {
        return taskIds.stream().map(project::task)
                .filter(task -> task.status() == WorkPackageStatus.READY)
                .filter(task -> eligible(task.id()))
                .map(this::taskView).toList();
    }

    public synchronized Map<String, Object> task(String taskId) {
        return taskView(project.task(taskId));
    }

    public synchronized Map<String, Object> integrationStatus() {
        return Map.of(
                "contextVersion", project.contextVersion(),
                "activeLeases", leases.activeLeases(),
                "events", project.events(),
                "reconciliationRequired", reconciliationRequired,
                "automaticallyMergeMain", false,
                "mergePolicy", "HUMAN_ONLY");
    }

    public synchronized Map<String, Object> workbenchSnapshot() {
        var state = new LinkedHashMap<String, Object>(integrationStatus());
        state.put("tasks", taskIds.stream().map(id -> {
            var view = new LinkedHashMap<String, Object>(taskView(project.task(id)));
            view.put("owner", assignmentOwners.get(id));
            view.put("dependencies", dependencies.getOrDefault(id, Set.of()).stream().sorted().toList());
            var gates = new LinkedHashMap<String, Boolean>();
            for (var gate : QualityGate.values()) gates.put(gate.name(), project.gatePassed(id, gate));
            view.put("gates", gates);
            return view;
        }).toList());
        return state;
    }

    public synchronized long contextVersion() {
        return project.contextVersion();
    }

    public synchronized Projection snapshot() {
        return new Projection(project.snapshot(), leases.activeLeases(), Map.copyOf(executions),
                Map.copyOf(assignmentOwners), Map.copyOf(assignmentEpochs));
    }

    public synchronized void restore(Projection projection) {
        project.restore(projection.project());
        leases = new PathLeaseRegistry(Clock.systemUTC(), pathPolicy);
        projection.leases().forEach(leases::restore);
        executions.clear();
        if (projection.executions() != null) executions.putAll(projection.executions());
        if (projection.assignmentOwners() != null) {
            assignmentOwners.clear(); assignmentOwners.putAll(projection.assignmentOwners());
        }
        if (projection.assignmentEpochs() != null) {
            assignmentEpochs.clear(); assignmentEpochs.putAll(projection.assignmentEpochs());
        }
    }

    public synchronized void requireReconciliation() { reconciliationRequired = true; }

    public record Projection(GovernedProject.State project, List<PathLease> leases, Map<String, Execution> executions,
            Map<String, String> assignmentOwners, Map<String, Long> assignmentEpochs) {}

    public record Execution(String taskId, String executionId, String branch, String path, String baseCommit,
            String commitSha) {
        TaskWorktree worktree() { return new TaskWorktree(taskId, executionId, branch, Path.of(path), baseCommit); }
    }

    public synchronized JsonNode prepareWrite(String name, JsonNode input) {
        var arguments = input.deepCopy();
        var prepared = (com.fasterxml.jackson.databind.node.ObjectNode) arguments;
        prepared.put("_journalVersion", 2);
        if (name.equals("claim_task")) {
            String taskId = requiredText(input, "taskId");
            prepared.put("_executionId", UUID.randomUUID().toString());
            prepared.put("_baseCommit", project.task(taskId).baseCommit());
        }
        return prepared;
    }

    public synchronized Map<String, Object> journalResult(Object response) {
        return Map.of("journalVersion", 2, "response", response, "projection", snapshot());
    }

    /** Trusted coordinator API; this value is deliberately not exposed by REST or MCP reads. */
    public synchronized String assignmentToken(String taskId) {
        project.task(taskId);
        try {
            var mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(tokenSecret, "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(mac.doFinal(
                    (taskId + ":" + assignmentEpochs.get(taskId)).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("Cannot derive assignment credential", exception);
        }
    }

    private Object claim(String taskId, JsonNode arguments) {
        TaskPackage task = project.task(taskId);
        if (task.status() != WorkPackageStatus.READY) {
            throw new IllegalStateException("Task is not READY: " + taskId);
        }
        if (!eligible(taskId)) throw new IllegalStateException("Task prerequisites are not satisfied: " + taskId);
        TaskWorktree worktree;
        try {
            worktree = git.createExecution(repository, requiredText(arguments, "_baseCommit"), taskId,
                    requiredText(arguments, "_executionId"), repository.getParent().resolve(".refactor-worktrees"));
        } catch (RuntimeException exception) {
            throw new ReconciliationRequiredException("Git execution creation requires reconciliation", exception);
        }
        releaseExecutionLeases(taskId);
        executions.put(taskId, new Execution(taskId, worktree.executionId(), worktree.branch(),
                worktree.path().toString(), worktree.baseCommit(), null));
        task.moveTo(WorkPackageStatus.CLAIMED);
        return Map.of("task", taskView(task), "contextVersion", project.contextVersion());
    }

    private LeaseReservation reserve(String taskId, JsonNode arguments) {
        requireLeaseEligible(taskId);
        String resourcePath = requiredText(arguments, "resourcePath");
        TaskPackage task = project.task(taskId);
        boolean authorized = task.allowedPaths().stream().anyMatch(path -> pathPolicy.covers(path, resourcePath))
                && task.forbiddenPaths().stream().noneMatch(path -> pathPolicy.covers(path, resourcePath));
        if (!authorized) throw new SecurityException("Task is not authorized for path " + resourcePath);
        return leases.reserve(new LeaseRequest(
                requiredText(arguments, "ownerId"),
                authorizedExecutionId(taskId, arguments),
                resourcePath,
                OwnershipMode.valueOf(arguments.path("mode").asText("EXCLUSIVE_WRITE")),
                Duration.ofSeconds(arguments.path("ttlSeconds").asLong(300))));
    }

    private PathLease heartbeat(String taskId, JsonNode arguments) {
        requireLeaseEligible(taskId);
        return leases.heartbeat(
                UUID.fromString(requiredText(arguments, "leaseId")),
                authorizedExecutionId(taskId, arguments),
                arguments.path("leaseVersion").asLong(),
                Duration.ofSeconds(arguments.path("ttlSeconds").asLong(300)));
    }

    private Map<String, Object> release(String taskId, JsonNode arguments) {
        leases.release(
                UUID.fromString(requiredText(arguments, "leaseId")),
                authorizedExecutionId(taskId, arguments),
                arguments.path("leaseVersion").asLong());
        return Map.of("released", true);
    }

    private Map<String, Object> publish(String taskId, SemanticAsset asset, JsonNode arguments) {
        Set<String> affected = textSet(arguments.path("affectedTaskIds"));
        if (affected.isEmpty()) affected = Set.of(taskId);
        affected = downstreamClosure(affected);
        project.publishSemanticChange(
                asset,
                arguments.path("assetVersion").asLong(project.contextVersion() + 1),
                affected,
                requiredText(arguments, "idempotencyKey") + ":domain");
        affected.forEach(this::releaseExecutionLeases);
        return Map.of("published", true, "affectedTaskIds", affected,
                "contextVersion", project.contextVersion());
    }

    private SubmissionResult submit(String taskId, JsonNode arguments) {
        TaskPackage task = project.task(taskId);
        if (!project.currentlyValid(taskId)) throw new IllegalStateException("Task requires semantic revalidation");
        if (task.status() != WorkPackageStatus.CLAIMED && task.status() != WorkPackageStatus.RUNNING) {
            throw new IllegalStateException("Only an active execution can submit changes");
        }
        Execution execution = executions.get(taskId);
        if (execution == null) throw new IllegalStateException("No verified execution is bound to this task");
        String commitSha = requiredText(arguments, "commitSha");
        List<String> changedPaths = git.verifiedChangedPaths(repository, execution.worktree(), commitSha);
        if (changedPaths.isEmpty()) throw new IllegalArgumentException("Submission has no changes");
        List<PathLease> active = leases.activeLeases();
        for (String path : changedPaths) {
            if (!path.equals(pathPolicy.normalize(path))) {
                throw new SecurityException("Git path cannot be safely represented by the repository path policy");
            }
            boolean owned = active.stream().anyMatch(lease -> lease.taskExecutionId().equals(execution.executionId())
                    && lease.mode() != OwnershipMode.READ && pathPolicy.covers(lease.resourcePath(), path));
            if (!owned) throw new SecurityException("No active execution write lease covers " + path);
        }
        SubmissionResult result = project.submitChangeset(
                taskId, changedPaths,
                requiredText(arguments, "idempotencyKey") + ":domain");
        if (result.accepted()) {
            if (task.status() == WorkPackageStatus.CLAIMED) task.moveTo(WorkPackageStatus.RUNNING);
            task.moveTo(WorkPackageStatus.SUBMITTED);
            executions.put(taskId, new Execution(taskId, execution.executionId(), execution.branch(), execution.path(),
                    execution.baseCommit(), commitSha));
        }
        return result;
    }

    private Map<String, Object> block(String taskId) {
        TaskPackage task = project.task(taskId);
        if (task.status().canTransitionTo(WorkPackageStatus.BLOCKED)) {
            task.moveTo(WorkPackageStatus.BLOCKED);
        }
        return Map.of("blocked", true, "task", taskView(task));
    }

    private Map<String, Object> reportEvent(String taskId, JsonNode arguments) {
        String type = requiredText(arguments, "type");
        TaskPackage task = project.task(taskId);
        switch (type) {
            case "TaskStatusChanged" -> {
                WorkPackageStatus target = WorkPackageStatus.valueOf(requiredText(arguments, "targetStatus"));
                if (target != WorkPackageStatus.RUNNING && target != WorkPackageStatus.CANCELLED) {
                    throw new SecurityException("Task credentials cannot perform governance transitions");
                }
                task.moveTo(target);
            }
            case "QualityGateReported", "TaskAccepted" ->
                    throw new SecurityException("Approval requires an authenticated human principal");
            default -> {
                return Map.of("recorded", true, "taskId", taskId, "type", type,
                        "contextVersion", project.contextVersion());
            }
        }
        return Map.of("recorded", true, "task", taskView(task), "type", type,
                "contextVersion", project.contextVersion());
    }

    private void authorize(String taskId, String token) {
        project.task(taskId);
        if (!java.security.MessageDigest.isEqual(assignmentToken(taskId).getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) throw new SecurityException("Invalid task token");
    }

    private boolean eligible(String taskId) {
        return project.currentlyValid(taskId) && dependencies.getOrDefault(taskId, Set.of()).stream()
                .allMatch(id -> project.task(id).status() == WorkPackageStatus.ACCEPTED && project.currentlyValid(id));
    }

    private Set<String> downstreamClosure(Set<String> roots) {
        Set<String> affected = new LinkedHashSet<>(roots);
        roots.forEach(project::task);
        boolean changed;
        do {
            changed = false;
            for (var entry : dependencies.entrySet()) {
                if (entry.getValue().stream().anyMatch(affected::contains)) changed |= affected.add(entry.getKey());
            }
        } while (changed);
        return affected;
    }

    private Object operator(String action, String taskId, JsonNode arguments) {
        String subject = requiredText(arguments, "actorSubject");
        Set<String> roles = textSet(arguments.path("actorRoles"));
        if (action.equals("assign_task")) {
            requireRole(roles, ProjectRole.PROJECT_MANAGER);
            String assignee = requiredText(arguments, "assignee");
            if (!assignee.equals(assignmentOwners.get(taskId))) {
                if (executions.containsKey(taskId)) throw new IllegalStateException("Cannot reassign an existing execution");
                assignmentOwners.put(taskId, assignee);
                assignmentEpochs.merge(taskId, 1L, Long::sum);
            }
            return Map.of("assigned", true, "taskId", taskId);
        }
        if (subject.equals(assignmentOwners.get(taskId))) throw new SecurityException("Executors cannot approve their own work");
        String key = requiredText(arguments, "idempotencyKey");
        if (action.equals("retry_task")) {
            requireRole(roles, ProjectRole.PROJECT_MANAGER);
            TaskPackage task = project.task(taskId);
            if (!project.currentlyValid(taskId) || (task.status() != WorkPackageStatus.BLOCKED
                    && task.status() != WorkPackageStatus.REJECTED)) {
                throw new IllegalStateException("Only a valid blocked or rejected task can be retried");
            }
            task.moveTo(WorkPackageStatus.READY);
            releaseExecutionLeases(taskId);
            executions.remove(taskId);
            assignmentEpochs.merge(taskId, 1L, Long::sum);
            project.recordEvidence(key + ":evidence", taskId, Map.of("actor", subject, "action", action));
            return Map.of("task", taskView(task));
        }
        if (action.equals("revalidate_task")) {
            requireRole(roles, ProjectRole.ARCHITECT);
            project.revalidateTask(taskId, key + ":domain");
            releaseExecutionLeases(taskId);
            executions.remove(taskId);
            assignmentEpochs.merge(taskId, 1L, Long::sum);
            return Map.of("task", taskView(project.task(taskId)));
        }
        ProjectRole role = switch (action) {
            case "start_review", "merge_proposal" -> ProjectRole.ENGINEERING_LEAD;
            case "start_testing" -> ProjectRole.QA_LEAD;
            case "accept_task" -> ProjectRole.BUSINESS_APPROVER;
            case "record_gate" -> QualityGate.valueOf(requiredText(arguments, "gate")).approvingRole();
            default -> throw new IllegalArgumentException("Unsupported governance command: " + action);
        };
        requireRole(roles, role);
        if (!project.currentlyValid(taskId)) throw new IllegalStateException("Task requires semantic revalidation");
        Execution execution = executions.get(taskId);
        if (execution == null || execution.commitSha() == null
                || !execution.commitSha().equals(requiredText(arguments, "commitSha"))) {
            throw new IllegalStateException("Approval must identify the submitted execution commit");
        }
        git.verifiedChangedPaths(repository, execution.worktree(), execution.commitSha());
        TaskPackage task = project.task(taskId);
        Object result;
        switch (action) {
            case "start_review" -> { task.moveTo(WorkPackageStatus.CODE_REVIEW); result = taskView(task); }
            case "start_testing" -> {
                if (!project.gatePassed(taskId, QualityGate.CODE_REVIEW)) {
                    throw new IllegalStateException("Code review must pass before testing");
                }
                task.moveTo(WorkPackageStatus.TESTING); result = taskView(task);
            }
            case "record_gate" -> {
                requiredText(arguments, "evidenceRef");
                if (!arguments.path("passed").isBoolean()) throw new IllegalArgumentException("passed must be boolean");
                project.recordGate(taskId, QualityGate.valueOf(requiredText(arguments, "gate")),
                        arguments.path("passed").asBoolean(), role, key + ":domain");
                if (!arguments.path("passed").asBoolean()) {
                    task.moveTo(WorkPackageStatus.REJECTED);
                    releaseExecutionLeases(taskId);
                }
                result = Map.of("recorded", true);
            }
            case "accept_task" -> {
                project.acceptTask(taskId); releaseExecutionLeases(taskId); result = taskView(task);
            }
            case "merge_proposal" -> {
                integrationMergeGuard.accept(taskId);
                result = project.createManualMergeProposal(taskId, execution.branch(), execution.commitSha());
            }
            default -> throw new IllegalArgumentException("Unsupported governance command: " + action);
        }
        project.recordEvidence(key + ":evidence", taskId, Map.of("actor", subject, "role", role.name(),
                "action", action, "executionId", execution.executionId(), "commitSha", execution.commitSha(),
                "evidenceRef", arguments.path("evidenceRef").asText("")));
        return result;
    }

    private static void requireRole(Set<String> roles, ProjectRole role) {
        if (!roles.contains(role.name())) throw new SecurityException("Required role: " + role);
    }

    private void releaseExecutionLeases(String taskId) {
        Execution execution = executions.get(taskId);
        String executionId = execution == null ? "exec-" + taskId : execution.executionId();
        leases.activeLeases().stream().filter(lease -> lease.taskExecutionId().equals(executionId))
                .forEach(lease -> leases.removeForRecovery(lease.leaseId()));
    }

    private void requireLeaseEligible(String taskId) {
        WorkPackageStatus status = project.task(taskId).status();
        if (!project.currentlyValid(taskId) || Set.of(WorkPackageStatus.ACCEPTED, WorkPackageStatus.CANCELLED,
                WorkPackageStatus.STALE, WorkPackageStatus.BLOCKED, WorkPackageStatus.REJECTED).contains(status)) {
            throw new IllegalStateException("Inactive or invalidated tasks cannot acquire or renew leases");
        }
    }

    private String authorizedExecutionId(String taskId, JsonNode arguments) {
        String executionId = executions.containsKey(taskId) ? executions.get(taskId).executionId() : "exec-" + taskId;
        if (arguments.has("taskExecutionId") && !executionId.equals(arguments.path("taskExecutionId").asText())) {
            throw new SecurityException("Execution does not belong to the authenticated task");
        }
        return executionId;
    }

    private void requireContextVersion(long expectedVersion) {
        if (expectedVersion != project.contextVersion()) {
            throw new IllegalStateException(
                    "Context version conflict: expected " + expectedVersion
                            + ", actual " + project.contextVersion());
        }
    }

    private static String requiredText(JsonNode arguments, String field) {
        String value = arguments.path(field).asText();
        if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }

    private static Set<String> textSet(JsonNode array) {
        Set<String> values = new LinkedHashSet<>();
        if (array != null && array.isArray()) array.forEach(value -> values.add(value.asText()));
        return values;
    }

    private Map<String, Object> taskView(TaskPackage task) {
        Map<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("id", task.id());
        view.put("baseCommit", task.baseCommit());
        view.put("contextVersion", task.contextVersion());
        view.put("contractVersion", task.contractVersion());
        view.put("schemaVersion", task.schemaVersion());
        view.put("adrVersion", task.adrVersion());
        view.put("allowedPaths", task.allowedPaths());
        view.put("forbiddenPaths", task.forbiddenPaths());
        view.put("workingDirectory", task.workingDirectory());
        view.put("acceptanceCommands", task.acceptanceCommands());
        view.put("status", task.status());
        view.put("currentlyValid", project.currentlyValid(task.id()));
        if (executions.containsKey(task.id())) view.put("execution", executions.get(task.id()));
        return Map.copyOf(view);
    }

    private void recover(AuditEventRepository auditEvents, ObjectMapper mapper) {
        for (PersistedAuditEvent event : auditEvents.findAll()) {
            if (event.status().equals("FAILED")) continue;
            try {
                JsonNode arguments = mapper.readTree(event.payload());
                if (event.status().equals("PENDING")) {
                    Projection before = snapshot();
                    Object result;
                    try {
                        if (event.eventType().equals("claim_task") && arguments.path("_journalVersion").asInt() != 2) {
                            throw new IllegalStateException("Legacy pending claim has no durable Git execution plan");
                        }
                        requireContextVersion(arguments.path("expectedVersion").asLong(event.version()));
                        result = executeWrite(event.eventType(), event.taskId(), arguments);
                    } catch (ReconciliationRequiredException exception) {
                        restore(before);
                        reconciliationRequired = true;
                        return;
                    } catch (IllegalArgumentException | IllegalStateException | SecurityException
                            | LeaseNotFoundException | LeaseOwnerMismatchException | LeaseVersionConflictException
                            | io.refactorcontrolplane.governance.GateNotSatisfiedException exception) {
                        restore(before);
                        if (!auditEvents.fail(event.idempotencyKey(), event.version())) {
                            throw new IllegalStateException("Cannot terminate recovered operation " + event.idempotencyKey());
                        }
                        continue;
                    }
                    Projection after = snapshot();
                    String resultJson = mapper.writeValueAsString(journalResult(result));
                    restore(before);
                    if (!auditEvents.complete(event.idempotencyKey(), event.version(), resultJson)) {
                        throw new IllegalStateException("Cannot complete recovered operation " + event.idempotencyKey());
                    }
                    restore(after);
                } else if (event.status().equals("COMPLETED")) {
                    if (event.result() == null) {
                        throw new IllegalStateException("Completed operation has no result: " + event.idempotencyKey());
                    }
                    JsonNode result = mapper.readTree(event.result());
                    if (result.path("journalVersion").asInt() == 2) {
                        restore(mapper.treeToValue(result.path("projection"), Projection.class));
                    } else {
                        applyCompleted(event.eventType(), arguments, result);
                    }
                }
            } catch (java.io.IOException exception) {
                throw new IllegalStateException("Cannot recover operation " + event.idempotencyKey(), exception);
            }
        }
    }

    private void applyCompleted(String name, JsonNode arguments, JsonNode result) {
        String taskId = arguments.path("taskId").asText();
        switch (name) {
            case "publish_contract_change", "publish_schema_change", "record_architecture_decision" -> {
                SemanticAsset asset = switch (name) {
                    case "publish_contract_change" -> SemanticAsset.API_CONTRACT;
                    case "publish_schema_change" -> SemanticAsset.DB_SCHEMA;
                    default -> SemanticAsset.ARCHITECTURE_DECISION;
                };
                Set<String> affected = textSet(arguments.path("affectedTaskIds"));
                if (affected.isEmpty()) affected = Set.of(taskId);
                project.restoreLegacySemanticChange(asset, arguments.path("assetVersion").asLong(project.contextVersion() + 1),
                        downstreamClosure(affected), requiredText(arguments, "idempotencyKey") + ":domain");
            }
            case "claim_task" -> {
                TaskPackage task = project.task(taskId);
                if (task.status() == WorkPackageStatus.READY) task.moveTo(WorkPackageStatus.CLAIMED);
                project.markLegacyUnverified(taskId);
            }
            case "submit_changeset" -> {
                TaskPackage task = project.task(taskId);
                if (result.path("accepted").asBoolean() && task.status() == WorkPackageStatus.RUNNING) {
                    task.moveTo(WorkPackageStatus.SUBMITTED);
                }
                project.markLegacyUnverified(taskId);
            }
            case "report_change_event" -> {
                TaskPackage task = project.task(taskId);
                String type = arguments.path("type").asText();
                if (type.equals("TaskStatusChanged") || type.equals("TaskAccepted")) {
                    WorkPackageStatus target = type.equals("TaskAccepted") ? WorkPackageStatus.ACCEPTED
                            : WorkPackageStatus.valueOf(requiredText(arguments, "targetStatus"));
                    if (task.status().canTransitionTo(target)) task.moveTo(target);
                }
                project.markLegacyUnverified(taskId);
            }
            case "reserve_paths" -> restoreLease(result.path("lease"));
            case "heartbeat" -> restoreLease(result);
            case "release_paths" -> leases.removeForRecovery(
                    UUID.fromString(requiredText(arguments, "leaseId")));
            default -> executeWrite(name, taskId, arguments);
        }
    }

    private void restoreLease(JsonNode lease) {
        if (lease == null || lease.isMissingNode() || lease.isNull()) return;
        leases.restore(new PathLease(
                UUID.fromString(lease.path("leaseId").asText()),
                lease.path("ownerId").asText(),
                lease.path("taskExecutionId").asText(),
                lease.path("resourcePath").asText(),
                OwnershipMode.valueOf(lease.path("mode").asText()),
                lease.path("version").asLong(),
                parseInstant(lease.path("startedAt")),
                parseInstant(lease.path("heartbeatAt")),
                parseInstant(lease.path("expiresAt"))));
    }

    private static Instant parseInstant(JsonNode value) {
        if (value.isNumber()) {
            java.math.BigDecimal epoch = value.decimalValue();
            long seconds = epoch.longValue();
            int nanos = epoch.subtract(java.math.BigDecimal.valueOf(seconds))
                    .movePointRight(9).intValue();
            return Instant.ofEpochSecond(seconds, nanos);
        }
        return Instant.parse(value.asText());
    }

    public static class ReconciliationRequiredException extends IllegalStateException {
        public ReconciliationRequiredException(String message, Throwable cause) { super(message, cause); }
    }
}
