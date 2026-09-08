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
import io.refactorcontrolplane.persistence.AuditEventRepository;
import io.refactorcontrolplane.persistence.PersistedAuditEvent;
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
    private final PathLeaseRegistry leases;
    private final RepositoryPathPolicy pathPolicy;
    private final List<String> taskIds;
    private final Map<String, String> assignmentTokens = new LinkedHashMap<>();

    public McpProjectRuntime(
            DemoProjectService projectService, AuditEventRepository auditEvents, ObjectMapper mapper) {
        var overview = projectService.overview();
        this.pathPolicy = RepositoryPathPolicy.caseInsensitive();
        this.project = new GovernedProject(overview.projectId(), Clock.systemUTC(), pathPolicy);
        this.leases = new PathLeaseRegistry(Clock.systemUTC(), pathPolicy);
        this.taskIds = overview.plan().workPackages().stream().map(work -> work.id()).toList();
        this.taskIds.forEach(taskId -> assignmentTokens.put(taskId, UUID.randomUUID().toString()));
        overview.plan().workPackages().forEach(work -> project.registerTask(new TaskPackage(
                work.id(), overview.plan().baseCommit(), 1, 1, 1, 1,
                work.allowedPaths(), Set.of(".git", "backend/data"), work.workingDirectory(),
                work.acceptanceCommands(),
                WorkPackageStatus.READY)));
        recover(auditEvents, mapper);
    }

    public synchronized Object execute(String name, JsonNode arguments) {
        String taskId = requiredText(arguments, "taskId");
        authorize(taskId, requiredText(arguments, "taskToken"));
        requireContextVersion(arguments.path("expectedVersion").asLong());
        return executeWrite(name, taskId, arguments);
    }

    public synchronized void validateToken(JsonNode arguments) {
        String taskId = requiredText(arguments, "taskId");
        authorize(taskId, requiredText(arguments, "taskToken"));
    }

    private Object executeWrite(String name, String taskId, JsonNode arguments) {
        return switch (name) {
            case "claim_task" -> claim(taskId);
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
                "automaticallyMergeMain", false,
                "mergePolicy", "HUMAN_ONLY");
    }

    public synchronized long contextVersion() {
        return project.contextVersion();
    }

    /** Trusted coordinator API; this value is deliberately not exposed by REST or MCP reads. */
    public synchronized String assignmentToken(String taskId) {
        project.task(taskId);
        return assignmentTokens.get(taskId);
    }

    private Object claim(String taskId) {
        TaskPackage task = project.task(taskId);
        if (task.status() != WorkPackageStatus.READY) {
            throw new IllegalStateException("Task is not READY: " + taskId);
        }
        task.moveTo(WorkPackageStatus.CLAIMED);
        return Map.of("task", taskView(task), "contextVersion", project.contextVersion());
    }

    private LeaseReservation reserve(String taskId, JsonNode arguments) {
        String resourcePath = requiredText(arguments, "resourcePath");
        TaskPackage task = project.task(taskId);
        boolean authorized = task.allowedPaths().stream().anyMatch(path -> pathPolicy.covers(path, resourcePath))
                && task.forbiddenPaths().stream().noneMatch(path -> pathPolicy.covers(path, resourcePath));
        if (!authorized) throw new SecurityException("Task is not authorized for path " + resourcePath);
        return leases.reserve(new LeaseRequest(
                requiredText(arguments, "ownerId"),
                arguments.path("taskExecutionId").asText("exec-" + taskId),
                resourcePath,
                OwnershipMode.valueOf(arguments.path("mode").asText("EXCLUSIVE_WRITE")),
                Duration.ofSeconds(arguments.path("ttlSeconds").asLong(300))));
    }

    private PathLease heartbeat(String taskId, JsonNode arguments) {
        return leases.heartbeat(
                UUID.fromString(requiredText(arguments, "leaseId")),
                arguments.path("taskExecutionId").asText("exec-" + taskId),
                arguments.path("leaseVersion").asLong(),
                Duration.ofSeconds(arguments.path("ttlSeconds").asLong(300)));
    }

    private Map<String, Object> release(String taskId, JsonNode arguments) {
        leases.release(
                UUID.fromString(requiredText(arguments, "leaseId")),
                arguments.path("taskExecutionId").asText("exec-" + taskId),
                arguments.path("leaseVersion").asLong());
        return Map.of("released", true);
    }

    private Map<String, Object> publish(String taskId, SemanticAsset asset, JsonNode arguments) {
        Set<String> affected = textSet(arguments.path("affectedTaskIds"));
        if (affected.isEmpty()) affected = Set.of(taskId);
        project.publishSemanticChange(
                asset,
                arguments.path("assetVersion").asLong(project.contextVersion() + 1),
                affected,
                requiredText(arguments, "idempotencyKey") + ":domain");
        return Map.of("published", true, "affectedTaskIds", affected,
                "contextVersion", project.contextVersion());
    }

    private SubmissionResult submit(String taskId, JsonNode arguments) {
        TaskPackage task = project.task(taskId);
        if (task.status() == WorkPackageStatus.CLAIMED) task.moveTo(WorkPackageStatus.RUNNING);
        SubmissionResult result = project.submitChangeset(
                taskId, List.copyOf(textSet(arguments.path("changedPaths"))),
                requiredText(arguments, "idempotencyKey") + ":domain");
        if (result.accepted() && task.status() == WorkPackageStatus.RUNNING) {
            task.moveTo(WorkPackageStatus.SUBMITTED);
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
            case "TaskStatusChanged" -> task.moveTo(WorkPackageStatus.valueOf(
                    requiredText(arguments, "targetStatus")));
            case "QualityGateReported" -> project.recordGate(
                    taskId,
                    QualityGate.valueOf(requiredText(arguments, "gate")),
                    arguments.path("passed").asBoolean(false),
                    ProjectRole.valueOf(requiredText(arguments, "role")),
                    requiredText(arguments, "idempotencyKey") + ":domain");
            case "TaskAccepted" -> project.acceptTask(taskId);
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
        if (!assignmentTokens.get(taskId).equals(token)) throw new SecurityException("Invalid task token");
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
        view.put("allowedPaths", task.allowedPaths());
        view.put("forbiddenPaths", task.forbiddenPaths());
        view.put("workingDirectory", task.workingDirectory());
        view.put("acceptanceCommands", task.acceptanceCommands());
        view.put("status", task.status());
        return Map.copyOf(view);
    }

    private void recover(AuditEventRepository auditEvents, ObjectMapper mapper) {
        for (PersistedAuditEvent event : auditEvents.findAll()) {
            if (event.status().equals("FAILED")) continue;
            try {
                JsonNode arguments = mapper.readTree(event.payload());
                if (event.status().equals("PENDING")) {
                    Object result = executeWrite(event.eventType(), event.taskId(), arguments);
                    String resultJson = mapper.writeValueAsString(result);
                    if (!auditEvents.complete(event.idempotencyKey(), event.version(), resultJson)) {
                        throw new IllegalStateException("Cannot complete recovered operation " + event.idempotencyKey());
                    }
                } else if (event.status().equals("COMPLETED")) {
                    if (event.result() == null) {
                        throw new IllegalStateException("Completed operation has no result: " + event.idempotencyKey());
                    }
                    applyCompleted(event.eventType(), arguments, mapper.readTree(event.result()));
                }
            } catch (java.io.IOException exception) {
                throw new IllegalStateException("Cannot recover operation " + event.idempotencyKey(), exception);
            }
        }
    }

    private void applyCompleted(String name, JsonNode arguments, JsonNode result) {
        String taskId = arguments.path("taskId").asText();
        switch (name) {
            case "claim_task" -> {
                TaskPackage task = project.task(taskId);
                if (task.status() == WorkPackageStatus.READY) task.moveTo(WorkPackageStatus.CLAIMED);
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
}
