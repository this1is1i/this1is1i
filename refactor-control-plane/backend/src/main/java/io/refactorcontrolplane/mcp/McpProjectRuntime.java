package io.refactorcontrolplane.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.refactorcontrolplane.project.ProjectSource;
import io.refactorcontrolplane.executor.ExecutionProtocol;
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
    private final ProjectSource source;
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
    private final Set<String> stoppedExecutions = new LinkedHashSet<>();
    private final Map<String, ExecutionProtocol.Run> executorRuns = new LinkedHashMap<>();
    private final ObjectMapper mapper;
    private final io.refactorcontrolplane.integration.GateEvidencePolicy evidencePolicy;
    private final io.refactorcontrolplane.executor.ExecutionModelEvidence modelEvidence;
    private final io.refactorcontrolplane.governance.EvidenceStore evidenceStore;
    private final Map<String, Map<String,String>> gateEvidence = new LinkedHashMap<>();
    private java.util.function.Consumer<String> integrationMergeGuard = taskId -> {};

    public synchronized void integrationMergeGuard(java.util.function.Consumer<String> guard) {
        integrationMergeGuard = guard;
    }

    public record IntegrationInput(String taskId, String owner, Execution execution, long contextVersion,
            long contractVersion, long schemaVersion, long adrVersion, List<String> dependencies) {}

    /** Captures authoritative, submitted executions in deterministic dependency order. */
    public synchronized List<IntegrationInput> integrationInputs(List<String> requested) {
        source.requireExecutable();
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
            executorRun(execution.executionId());
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
        for (String id : ids) {
            if (project.task(id).status() != WorkPackageStatus.ACCEPTED)
                throw new IllegalStateException("Task has not completed human acceptance: " + id);
            requireGateEvidence(id,Set.of(QualityGate.values()));
        }
    }

    public McpProjectRuntime(
            ProjectSource projectService, AuditEventRepository auditEvents, ObjectMapper mapper) {
        this(projectService,auditEvents,mapper,null);
    }
    public McpProjectRuntime(ProjectSource projectService, AuditEventRepository auditEvents, ObjectMapper mapper,
            io.refactorcontrolplane.integration.GateEvidencePolicy evidencePolicy) {
        this(projectService, auditEvents, mapper, evidencePolicy, null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public McpProjectRuntime(ProjectSource projectService, AuditEventRepository auditEvents, ObjectMapper mapper,
            io.refactorcontrolplane.integration.GateEvidencePolicy evidencePolicy,
            io.refactorcontrolplane.governance.EvidenceStore evidenceStore) {
        this.source = projectService;
        this.mapper = mapper;
        this.evidencePolicy = evidencePolicy;
        this.evidenceStore = evidenceStore;
        this.modelEvidence = evidenceStore == null ? null : new io.refactorcontrolplane.executor.ExecutionModelEvidence(evidenceStore, mapper);
        if (source.configured()) integrationMergeGuard=taskId -> {
            if (evidencePolicy==null) throw new IllegalStateException("Evidence verifier is unavailable");
            evidencePolicy.requireSingleTaskProposal(taskId);
        };
        auditEvents.requireProjectMode(source.configured());
        auditEvents.bindScope(source.planScope());
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
                work.allowedPaths(), source.forbiddenPaths(), work.workingDirectory(),
                work.acceptanceCommands(),
                WorkPackageStatus.READY)));
        recover(auditEvents, mapper);
    }

    public synchronized Object execute(String name, JsonNode arguments) {
        source.requireExecutable();
        if (reconciliationRequired) throw new IllegalStateException("Operation journal requires restart reconciliation");
        String taskId = requiredText(arguments, "taskId");
        authorize(taskId, requiredText(arguments, "taskToken"));
        requireContextVersion(arguments.path("expectedVersion").asLong());
        return executeWrite(name, taskId, arguments);
    }

    public synchronized void validateToken(JsonNode arguments) {
        source.requireExecutable();
        if (reconciliationRequired) throw new IllegalStateException("Operation journal requires restart reconciliation");
        String taskId = requiredText(arguments, "taskId");
        authorize(taskId, requiredText(arguments, "taskToken"));
    }

    private Object executeWrite(String name, String taskId, JsonNode arguments) {
        if (name.startsWith("executor:")) return executorCommand(name.substring(9), taskId, arguments);
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
        if (!source.approved()) return List.of();
        return taskIds.stream().map(project::task)
                .filter(task -> task.status() == WorkPackageStatus.READY)
                .filter(task -> eligible(task.id()))
                .filter(task -> capacityBlockers(task.id()).isEmpty())
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
        state.put("evidenceRequired",source.configured());
        state.put("tasks", taskIds.stream().map(id -> {
            var view = new LinkedHashMap<String, Object>(taskView(project.task(id)));
            view.put("owner", assignmentOwners.get(id));
            view.put("gateEvidence",Map.copyOf(gateEvidence.getOrDefault(id,Map.of())));
            Execution execution = executions.get(id);
            if (execution != null && executorRuns.containsKey(execution.executionId())) view.put("executor", executorRun(execution.executionId()));
            view.put("claimable", source.approved() && !reconciliationRequired && project.task(id).status() == WorkPackageStatus.READY
                    && eligible(id) && capacityBlockers(id).isEmpty());
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
                Map.copyOf(assignmentOwners), Map.copyOf(assignmentEpochs), Set.copyOf(stoppedExecutions), Map.copyOf(executorRuns),
                gateEvidence.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey,entry->Map.copyOf(entry.getValue()))));
    }

    /** Audit reads must not expire leases or advance their watermark. Never serialize this raw projection. */
    public synchronized Projection auditSnapshot() {
        return new Projection(project.snapshot(), List.of(), Map.copyOf(executions), Map.copyOf(assignmentOwners),
                Map.copyOf(assignmentEpochs), Set.copyOf(stoppedExecutions), Map.copyOf(executorRuns),
                gateEvidence.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> Map.copyOf(e.getValue()))));
    }
    public synchronized boolean auditReconciliationRequired() { return reconciliationRequired; }

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
        stoppedExecutions.clear();
        if (projection.stoppedExecutions() != null) stoppedExecutions.addAll(projection.stoppedExecutions());
        executorRuns.clear();
        if (projection.executorRuns() != null) executorRuns.putAll(projection.executorRuns());
        gateEvidence.clear();
        if (projection.gateEvidence()!=null) projection.gateEvidence().forEach((task,refs)->gateEvidence.put(task,new LinkedHashMap<>(refs)));
    }

    public synchronized void requireReconciliation() { reconciliationRequired = true; }

    public synchronized void requirePlanSwitchSafe() {
        if (reconciliationRequired || !leases.activeLeases().isEmpty()) throw new IllegalStateException("Resolve the journal and active leases before switching plans");
        if (source.scheduling() != null && executions.values().stream().anyMatch(this::holdsCapacity))
            throw new IllegalStateException("Confirm unfinished executions have stopped before switching plans");
        for (String id : taskIds) if (!Set.of(WorkPackageStatus.READY, WorkPackageStatus.ACCEPTED, WorkPackageStatus.CANCELLED).contains(project.task(id).status()))
            throw new IllegalStateException("Finish or cancel task before switching plans: " + id);
    }

    public record Projection(GovernedProject.State project, List<PathLease> leases, Map<String, Execution> executions,
            Map<String, String> assignmentOwners, Map<String, Long> assignmentEpochs, Set<String> stoppedExecutions,
            Map<String, ExecutionProtocol.Run> executorRuns, Map<String,Map<String,String>> gateEvidence) {
        public Projection(GovernedProject.State project,List<PathLease> leases,Map<String,Execution> executions,
                Map<String,String> owners,Map<String,Long> epochs,Set<String> stopped,Map<String,ExecutionProtocol.Run> runs) {
            this(project,leases,executions,owners,epochs,stopped,runs,Map.of());
        }
    }

    public record Execution(String taskId, String executionId, String branch, String path, String baseCommit,
            String commitSha) {
        TaskWorktree worktree() { return new TaskWorktree(taskId, executionId, branch, Path.of(path), baseCommit); }
    }

    public synchronized JsonNode prepareWrite(String name, JsonNode input) {
        if (source.configured() && Set.of("publish_contract_change", "publish_schema_change").contains(name))
            semanticPublication(requiredText(input, "taskId"), name.equals("publish_contract_change") ? SemanticAsset.API_CONTRACT : SemanticAsset.DB_SCHEMA, input);
        if (name.equals("executor:observe")) {
            io.refactorcontrolplane.executor.ExecutorModelRun.validate(input.path("result"));
            try {
                var result = mapper.treeToValue(input.path("result"), ExecutionProtocol.Result.class);
                if (result.modelRuns() != null && !result.modelRuns().isEmpty()) {
                    if (modelEvidence == null) throw new IllegalStateException("Model evidence storage is unavailable");
                    var run = executorRun(requiredText(input, "taskExecutionId"));
                    if (run == null) throw new IllegalStateException("Unknown executor run");
                    modelEvidence.validate(run.request(), result);
                }
            }
            catch (java.io.IOException ex) { throw new IllegalArgumentException("Invalid executor result", ex); }
        }
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

    public synchronized long requireAssignedSubject(String taskId, String subjectId) {
        project.task(taskId);
        if (!subjectId.equals(assignmentOwners.get(taskId))) throw new SecurityException("Only the assigned subject can claim this task");
        return assignmentEpochs.get(taskId);
    }

    private Object claim(String taskId, JsonNode arguments) {
        TaskPackage task = project.task(taskId);
        if (task.status() != WorkPackageStatus.READY) {
            throw new IllegalStateException("Task is not READY: " + taskId);
        }
        if (!eligible(taskId)) throw new IllegalStateException("Task prerequisites are not satisfied: " + taskId);
        var blockers = capacityBlockers(taskId);
        if (!blockers.isEmpty()) throw new IllegalStateException("Execution capacity unavailable: " + blockers);
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
        String sourceEvidence = null;
        if (source.configured() && asset != SemanticAsset.ARCHITECTURE_DECISION) {
            var publication = semanticPublication(taskId, asset, arguments); affected = publication.affected();
            try { sourceEvidence = evidenceStore.append(publication.key(), publication.binding(), "SEMANTIC_SOURCE_CHANGE", assignmentOwners.get(taskId), publication.content()).id(); }
            catch (RuntimeException ex) { throw new ReconciliationRequiredException("Semantic evidence write requires reconciliation", ex); }
        }
        if (affected.isEmpty()) affected = Set.of(taskId);
        affected = downstreamClosure(affected);
        project.publishSemanticChange(
                asset,
                arguments.path("assetVersion").asLong(project.contextVersion() + 1),
                affected,
                requiredText(arguments, "idempotencyKey") + ":domain");
        affected.forEach(this::releaseExecutionLeases);
        if (sourceEvidence != null) {
            project.recordEvidence(requiredText(arguments, "idempotencyKey") + ":source", taskId,
                    Map.of("sourceEvidenceRef", sourceEvidence, "asset", asset.name(), "executionId", executions.get(taskId).executionId(), "commitSha", executions.get(taskId).commitSha()));
            return Map.of("published", true, "affectedTaskIds", affected, "contextVersion", project.contextVersion(), "sourceEvidenceRef", sourceEvidence);
        }
        return Map.of("published", true, "affectedTaskIds", affected,
                "contextVersion", project.contextVersion());
    }

    private record SemanticPublication(String key, io.refactorcontrolplane.governance.EvidenceStore.Binding binding,
            Set<String> affected, JsonNode content) {}
    private SemanticPublication semanticPublication(String taskId, SemanticAsset asset, JsonNode arguments) {
        var binding = evidenceBinding(taskId);
        if (!binding.executionId().equals(requiredText(arguments, "taskExecutionId")) || !binding.commitSha().equals(requiredText(arguments, "commitSha")))
            throw new IllegalStateException("Semantic publication requires the exact submitted execution and commit");
        Set<String> resources = boundedStrings(arguments.path("resourceIds")), artifacts = boundedStrings(arguments.path("artifactPaths"));
        if (!source.semanticResources(taskId, asset.name()).containsAll(resources)) throw new SecurityException("Semantic resources are not declared for this task");
        Execution execution = executions.get(taskId);
        var changed = git.verifiedChangedPaths(repository, execution.worktree(), execution.commitSha());
        if (!changed.containsAll(artifacts) || artifacts.stream().anyMatch(path -> !path.equals(pathPolicy.normalize(path))))
            throw new IllegalArgumentException("Semantic artifacts must be exact paths changed by the submitted execution");
        var consumers = new LinkedHashSet<>(textSet(arguments.path("affectedTaskIds"))); consumers.addAll(source.semanticConsumers(resources));
        Set<String> affected = downstreamClosure(consumers); affected.forEach(project::task);
        var content = mapper.createObjectNode().put("provenance", "TASK_DECLARED_SOURCE_LINK").put("asset", asset.name())
                .put("assetVersion", arguments.path("assetVersion").asLong(project.contextVersion() + 1));
        content.set("resourceIds", mapper.valueToTree(resources.stream().sorted().toList()));
        content.set("artifactPaths", mapper.valueToTree(artifacts.stream().sorted().toList()));
        content.set("affectedTaskIds", mapper.valueToTree(affected.stream().sorted().toList()));
        if (evidenceStore == null) throw new IllegalStateException("Semantic evidence storage is unavailable");
        String key = "semantic:" + io.refactorcontrolplane.executor.ExecutorSettings.hash(requiredText(arguments, "idempotencyKey"));
        evidenceStore.validateAppend(key, binding, "SEMANTIC_SOURCE_CHANGE", assignmentOwners.get(taskId), content);
        return new SemanticPublication(key, binding, affected, content);
    }

    private static Set<String> boundedStrings(JsonNode value) {
        if (!value.isArray() || value.isEmpty() || value.size() > 20) throw new IllegalArgumentException("Supply 1 to 20 semantic resources and artifact paths");
        var values = new LinkedHashSet<String>();
        for (var item : value) {
            if (!item.isTextual() || item.asText().isBlank() || item.asText().length() > 1000 || !values.add(item.asText()))
                throw new IllegalArgumentException("Semantic references must be distinct nonblank strings");
        }
        return Set.copyOf(values);
    }

    private SubmissionResult submit(String taskId, JsonNode arguments) {
        TaskPackage task = project.task(taskId);
        if (!project.currentlyValid(taskId)) throw new IllegalStateException("Task requires semantic revalidation");
        if (task.status() != WorkPackageStatus.CLAIMED && task.status() != WorkPackageStatus.RUNNING) {
            throw new IllegalStateException("Only an active execution can submit changes");
        }
        Execution execution = executions.get(taskId);
        if (execution == null) throw new IllegalStateException("No verified execution is bound to this task");
        if (executorRuns.containsKey(execution.executionId()) && !arguments.path("_executorVerified").asBoolean())
            throw new IllegalStateException("Managed executions must submit through the bound executor result");
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
            gateEvidence.remove(taskId);
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

    public record CapacityBlocker(String kind, String resourceId, int capacity, long used) {}

    public synchronized ExecutionProtocol.Run executorRun(String id) {
        var run = executorRuns.get(id);
        if (run != null && (run.modelEvidenceRef() != null || (run.result() != null && run.result().modelRuns() != null && !run.result().modelRuns().isEmpty()))) {
            if (modelEvidence == null) throw new IllegalStateException("Model evidence verifier is unavailable");
            modelEvidence.verify(run);
        }
        return run;
    }
    // Local delivery truth, deliberately excluded from durable projections: recovery never redelivers.
    private final Set<String> dispatchingExecutions = new java.util.HashSet<>();
    public synchronized void beginExecutorDelivery(String id) {
        if (!dispatchingExecutions.add(id)) throw new IllegalStateException("Executor delivery already in progress");
    }
    public synchronized void endExecutorDelivery(String id) { dispatchingExecutions.remove(id); }
    public synchronized List<ExecutionProtocol.Run> activeExecutorRuns() {
        return executorRuns.values().stream().filter(run -> !run.terminal()).toList();
    }
    private void requireExecutorBinding(ExecutionProtocol.Request request) {
        Execution current = executions.get(request.execution().taskId());
        if (current == null || !current.executionId().equals(request.execution().executionId())
                || !source.planScope().equals(request.planScope()) || request.contextVersion() != project.contextVersion()
                || !java.util.Objects.equals(assignmentEpochs.get(current.taskId()), request.assignmentEpoch())
                || !java.util.Objects.equals(assignmentOwners.get(current.taskId()), request.owner())
                || !project.currentlyValid(current.taskId()) || stoppedExecutions.contains(current.executionId()))
            throw new IllegalStateException("Executor result belongs to an obsolete or stopped execution");
    }
    private Object executorCommand(String action, String taskId, JsonNode arguments) {
        try {
            String id = requiredText(arguments, "taskExecutionId");
            if (action.equals("prepare")) {
                var request = mapper.treeToValue(arguments.path("request"), ExecutionProtocol.Request.class);
                requireExecutorBinding(request);
                if (!request.execution().taskId().equals(taskId) || !request.execution().executionId().equals(id)
                        || source.scheduling() == null || project.task(taskId).status() != WorkPackageStatus.CLAIMED
                        || executorRuns.containsKey(id) || !request.execution().equals(executions.get(taskId))
                        || !request.allowedPaths().equals(project.task(taskId).allowedPaths())
                        || !request.forbiddenPaths().equals(project.task(taskId).forbiddenPaths()))
                    throw new IllegalStateException("Executor requires a fresh claimed execution and configured capacity");
                long seconds = Duration.between(Instant.now(), request.deadline()).toSeconds();
                if (seconds < 1 || seconds > 86400) throw new IllegalArgumentException("Invalid execution deadline");
                for (String path : request.allowedPaths()) {
                    var reservation = leases.reserve(new LeaseRequest(request.owner(), id, path, OwnershipMode.EXCLUSIVE_WRITE, Duration.ofSeconds(seconds)));
                    if (!reservation.accepted()) throw new IllegalStateException("Executor paths are leased by another execution");
                }
                project.task(taskId).moveTo(WorkPackageStatus.RUNNING);
                var run = new ExecutionProtocol.Run(request, "UNKNOWN", null); executorRuns.put(id, run); return run;
            }
            if (!Set.of("observe", "unknown").contains(action)) throw new IllegalArgumentException("Unknown executor operation");
            var run = executorRuns.get(id);
            if (run == null || !run.request().execution().taskId().equals(taskId)) throw new IllegalStateException("Unknown executor run");
            requireExecutorBinding(run.request());
            if (action.equals("unknown")) {
                if (run.terminal()) return run;
                var unknown = new ExecutionProtocol.Run(run.request(), "UNKNOWN", run.result()); executorRuns.put(id, unknown); return unknown;
            }
            var result = mapper.treeToValue(arguments.path("result"), ExecutionProtocol.Result.class);
            if (!id.equals(result.executionId()) || !run.request().requestHash().equals(result.requestHash())
                    || result.sequence() < 1 || !Set.of("RUNNING", "SUCCEEDED", "FAILED").contains(result.state()))
                throw new IllegalStateException("Executor result binding or state is invalid");
            if (run.result() != null && result.sequence() <= run.result().sequence()) {
                if (!result.equals(run.result())) throw new IllegalStateException("Executor result sequence is stale or contradictory");
                if (run.terminal()) { executorRun(id); return run; }
                var heartbeat = new ExecutionProtocol.Run(run.request(), result.state(), result); executorRuns.put(id, heartbeat); return heartbeat;
            }
            if (run.terminal()) throw new IllegalStateException("A terminal execution cannot be replaced");
            if (result.state().equals("SUCCEEDED")) {
                if (result.checks().size() != run.request().checks().size()
                        || result.checks().stream().map(ExecutionProtocol.CheckResult::id).distinct().count() != result.checks().size()
                        || result.checks().stream().anyMatch(check -> check.exitCode() != 0 || check.timedOut() || check.truncated())
                        || !result.checks().stream().map(ExecutionProtocol.CheckResult::id).collect(java.util.stream.Collectors.toSet())
                            .equals(run.request().checks().stream().map(io.refactorcontrolplane.integration.IntegrationProfiles.Check::id).collect(java.util.stream.Collectors.toSet())))
                    throw new IllegalStateException("Executor did not pass all approved checks");
                // A terminal result can arrive after restart/expiry. Reconcile ownership without
                // renewing a live worker or displacing any lease acquired by another execution.
                var active = leases.activeLeases();
                for (String path : run.request().allowedPaths()) {
                    if (active.stream().anyMatch(lease -> lease.taskExecutionId().equals(id)
                            && lease.mode() == OwnershipMode.EXCLUSIVE_WRITE && pathPolicy.covers(lease.resourcePath(), path))) continue;
                    var reservation = leases.reserve(new LeaseRequest(run.request().owner(), id, path,
                            OwnershipMode.EXCLUSIVE_WRITE, Duration.ofSeconds(300)));
                    if (!reservation.accepted()) throw new IllegalStateException("Reconciliation paths are leased by another execution");
                }
                var submission = ((com.fasterxml.jackson.databind.node.ObjectNode) arguments).deepCopy();
                submission.put("commitSha", result.commitSha()).put("_executorVerified", true);
                if (!submit(taskId, submission).accepted()) throw new IllegalStateException("Executor changed unauthorized paths");
            } else if (result.state().equals("FAILED")) {
                block(taskId); stoppedExecutions.add(id); releaseExecutionLeases(taskId); assignmentEpochs.merge(taskId, 1L, Long::sum);
            }
            String modelRef = null;
            if (result.modelRuns() != null && !result.modelRuns().isEmpty()) {
                if (modelEvidence == null) throw new IllegalStateException("Model evidence storage is unavailable");
                try { modelRef = modelEvidence.record(run.request(), result, evidenceBinding(taskId)); }
                catch (RuntimeException ex) { throw new ReconciliationRequiredException("Model evidence write requires reconciliation", ex); }
            }
            var updated = new ExecutionProtocol.Run(run.request(), result.state(), result, Instant.now().toString(), modelRef);
            executorRuns.put(id, updated); return updated;
        } catch (java.io.IOException ex) { throw new IllegalArgumentException("Invalid executor payload"); }
    }

    private boolean holdsCapacity(Execution execution) {
        // BLOCKED, CANCELLED, STALE and elapsed lease TTL do not prove the worker stopped.
        return execution.commitSha() == null && !stoppedExecutions.contains(execution.executionId());
    }

    private List<CapacityBlocker> capacityBlockers(String taskId) {
        var policy = source.scheduling();
        if (policy == null || project.task(taskId).status() != WorkPackageStatus.READY) return List.of();
        var active = executions.values().stream().filter(this::holdsCapacity).toList();
        var blockers = new java.util.ArrayList<CapacityBlocker>();
        String actor = assignmentOwners.get(taskId);
        int capacity = policy.actorCapacities().getOrDefault(actor, 0);
        long used = active.stream().filter(execution -> actor.equals(assignmentOwners.get(execution.taskId()))).count();
        if (used >= capacity) blockers.add(new CapacityBlocker("ACTOR", actor, capacity, used));
        for (String environment : source.taskEnvironments(taskId).stream().sorted().toList()) {
            capacity = policy.environmentCapacities().getOrDefault(environment, 0);
            used = active.stream().filter(execution -> source.taskEnvironments(execution.taskId()).contains(environment)).count();
            if (used >= capacity) blockers.add(new CapacityBlocker("ENVIRONMENT", environment, capacity, used));
        }
        return List.copyOf(blockers);
    }

    private void requireCapacityReleased(String taskId) {
        Execution execution = executions.get(taskId);
        if (source.scheduling() != null && execution != null && holdsCapacity(execution))
            throw new IllegalStateException("Confirm this execution has stopped before retry or revalidation");
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
        if (action.equals("confirm_execution_stopped")) {
            requireRole(roles, ProjectRole.PROJECT_MANAGER);
            Execution execution = executions.get(taskId);
            if (execution == null || !execution.executionId().equals(requiredText(arguments, "taskExecutionId")))
                throw new SecurityException("Stop confirmation must identify the current execution");
            if (dispatchingExecutions.contains(execution.executionId()))
                throw new IllegalStateException("Wait for in-flight executor delivery before confirming it stopped");
            TaskPackage task = project.task(taskId);
            if (!Set.of(WorkPackageStatus.CLAIMED, WorkPackageStatus.RUNNING, WorkPackageStatus.BLOCKED,
                    WorkPackageStatus.CANCELLED, WorkPackageStatus.STALE).contains(task.status()))
                throw new IllegalStateException("Stop confirmation requires an unfinished execution");
            String evidence = requiredText(arguments, "evidenceRef");
            if (task.status() == WorkPackageStatus.CLAIMED || task.status() == WorkPackageStatus.RUNNING)
                task.moveTo(WorkPackageStatus.BLOCKED);
            if (stoppedExecutions.add(execution.executionId())) assignmentEpochs.merge(taskId, 1L, Long::sum);
            var managed = executorRuns.get(execution.executionId());
            if (managed != null && !managed.terminal()) executorRuns.put(execution.executionId(),
                    new ExecutionProtocol.Run(managed.request(), "STOPPED", managed.result()));
            releaseExecutionLeases(taskId);
            project.recordEvidence(key + ":evidence", taskId, Map.of("actor", subject, "action", action,
                    "executionId", execution.executionId(), "evidenceRef", evidence));
            return Map.of("task", taskView(project.task(taskId)));
        }
        if (action.equals("retry_task")) {
            requireRole(roles, ProjectRole.PROJECT_MANAGER);
            TaskPackage task = project.task(taskId);
            if (!project.currentlyValid(taskId) || (task.status() != WorkPackageStatus.BLOCKED
                    && task.status() != WorkPackageStatus.REJECTED)) {
                throw new IllegalStateException("Only a valid blocked or rejected task can be retried");
            }
            requireCapacityReleased(taskId);
            task.moveTo(WorkPackageStatus.READY);
            releaseExecutionLeases(taskId);
            executions.remove(taskId);
            gateEvidence.remove(taskId);
            assignmentEpochs.merge(taskId, 1L, Long::sum);
            project.recordEvidence(key + ":evidence", taskId, Map.of("actor", subject, "action", action));
            return Map.of("task", taskView(task));
        }
        if (action.equals("revalidate_task")) {
            requireRole(roles, ProjectRole.ARCHITECT);
            requireCapacityReleased(taskId);
            project.revalidateTask(taskId, key + ":domain");
            gateEvidence.remove(taskId);
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
                requireGateEvidence(taskId,Set.of(QualityGate.CODE_REVIEW));
                if (!project.gatePassed(taskId, QualityGate.CODE_REVIEW)) {
                    throw new IllegalStateException("Code review must pass before testing");
                }
                task.moveTo(WorkPackageStatus.TESTING); result = taskView(task);
            }
            case "record_gate" -> {
                if (source.configured() && !execution.executionId().equals(requiredText(arguments,"taskExecutionId")))
                    throw new IllegalStateException("Gate approval must identify the exact submitted execution");
                if (!arguments.path("passed").isBoolean()) throw new IllegalArgumentException("passed must be boolean");
                QualityGate gate=QualityGate.valueOf(requiredText(arguments,"gate"));
                boolean passed=arguments.path("passed").asBoolean();
                boolean renewal=source.configured() && passed && project.gatePassed(taskId,gate)
                        && (task.status()==WorkPackageStatus.ACCEPTED || (task.status()==WorkPackageStatus.TESTING && gate==QualityGate.CODE_REVIEW));
                if (!renewal && task.status()!=(gate==QualityGate.CODE_REVIEW?WorkPackageStatus.CODE_REVIEW:WorkPackageStatus.TESTING))
                    throw new IllegalStateException("Gate is not available in this task state");
                String ref;
                if (source.configured()) {
                    if (evidencePolicy==null) throw new IllegalStateException("Evidence verifier is unavailable");
                    ref=evidencePolicy.record(key,evidenceBinding(taskId),gate,passed,subject,assignmentOwners.get(taskId),arguments,this::integrationInputs);
                } else ref=requiredText(arguments,"evidenceRef");
                if (!renewal) project.recordGate(taskId,gate,passed,role,key+":domain");
                gateEvidence.computeIfAbsent(taskId,ignored->new LinkedHashMap<>()).put(gate.name(),ref);
                if (!arguments.path("passed").asBoolean()) {
                    task.moveTo(WorkPackageStatus.REJECTED);
                    releaseExecutionLeases(taskId);
                }
                result = Map.of("recorded", true,"evidenceRef",ref);
            }
            case "accept_task" -> {
                requireGateEvidence(taskId,Set.of(QualityGate.values()));
                project.acceptTask(taskId); releaseExecutionLeases(taskId); result = taskView(task);
            }
            case "merge_proposal" -> {
                requireGateEvidence(taskId,Set.of(QualityGate.values()));
                integrationMergeGuard.accept(taskId);
                result = project.createManualMergeProposal(taskId, execution.branch(), execution.commitSha());
            }
            default -> throw new IllegalArgumentException("Unsupported governance command: " + action);
        }
        project.recordEvidence(key + ":evidence", taskId, Map.of("actor", subject, "role", role.name(),
                "action", action, "executionId", execution.executionId(), "commitSha", execution.commitSha(),
                "evidenceRef", action.equals("record_gate")?gateEvidence.get(taskId).get(requiredText(arguments,"gate")):arguments.path("evidenceRef").asText("")));
        return result;
    }

    private io.refactorcontrolplane.governance.EvidenceStore.Binding evidenceBinding(String taskId) {
        var task=project.task(taskId); var execution=executions.get(taskId);
        if (!project.currentlyValid(taskId) || execution==null || execution.commitSha()==null) throw new IllegalStateException("Evidence requires a current submitted execution");
        git.verifiedChangedPaths(repository,execution.worktree(),execution.commitSha());
        return new io.refactorcontrolplane.governance.EvidenceStore.Binding(source.planScope(),taskId,execution.executionId(),execution.commitSha(),
                task.contextVersion(),task.contractVersion(),task.schemaVersion(),task.adrVersion());
    }
    private void requireGateEvidence(String taskId,Set<QualityGate> gates) {
        if (!source.configured()) return;
        if (evidencePolicy==null) throw new IllegalStateException("Evidence verifier is unavailable");
        var binding=evidenceBinding(taskId);
        for (QualityGate gate:gates) {
            if (!project.gatePassed(taskId,gate)) throw new IllegalStateException("Gate has not passed: "+gate);
            evidencePolicy.verify(gateEvidence.getOrDefault(taskId,Map.of()).get(gate.name()),binding,gate,true,null,assignmentOwners.get(taskId),this::integrationInputs);
        }
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
        view.put("capacityBlockers", capacityBlockers(task.id()));
        view.put("capacityHeld", source.scheduling() != null && executions.containsKey(task.id()) && holdsCapacity(executions.get(task.id())));
        if (executions.containsKey(task.id())) view.put("execution", executions.get(task.id()));
        return Map.copyOf(view);
    }

    private void recover(AuditEventRepository auditEvents, ObjectMapper mapper) {
        if (source.configured() && !auditEvents.findAll().isEmpty()) source.requireExecutable();
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
