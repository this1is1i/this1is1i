package io.refactorcontrolplane.api;

import io.refactorcontrolplane.governance.LocalIdentityRegistry;
import io.refactorcontrolplane.integration.IntegrationProfiles;
import io.refactorcontrolplane.mcp.McpProjectRuntime;
import io.refactorcontrolplane.mcp.ProjectMcpController;
import io.refactorcontrolplane.mcp.McpToolSchemas;
import io.refactorcontrolplane.governance.ProjectRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class WorkbenchController {
    private final LocalIdentityRegistry identities;
    private final McpProjectRuntime runtime;
    private final IntegrationProfiles profiles;
    private final String projectId;
    private final ProjectMcpController journal;
    private final ObjectMapper mapper;
    private final io.refactorcontrolplane.executor.ExecutorSettings executors;
    private final io.refactorcontrolplane.project.ProjectSource source;
    public WorkbenchController(LocalIdentityRegistry identities, McpProjectRuntime runtime,
            IntegrationProfiles profiles, io.refactorcontrolplane.project.ProjectSource project, ProjectMcpController journal, ObjectMapper mapper,
            io.refactorcontrolplane.executor.ExecutorSettings executors) {
        this.identities = identities; this.runtime = runtime; this.profiles = profiles;
        this.projectId = project.overview().projectId();
        this.journal = journal; this.mapper = mapper;
        this.executors = executors;
        this.source = project;
    }
    @GetMapping("/api/v1/workbench")
    public ResponseEntity<?> snapshot(@RequestHeader(value="Authorization", required=false) String authorization) {
        try {
            var actor = identities.authenticate(authorization);
            if (!actor.projectId().equals(projectId)) throw new SecurityException("Project membership required");
            var result = new LinkedHashMap<String, Object>(runtime.workbenchSnapshot());
            result.put("projectId", projectId); result.put("subjectId", actor.subjectId());
            result.put("planScope",source.planScope()); result.put("auditExportSupported",source.configured());
            result.put("executors", executors.available());
            result.put("roles", actor.roles().stream().map(Enum::name).sorted().toList());
            if (actor.roles().contains(ProjectRole.PROJECT_MANAGER)) result.put("assignees", identities.projectMembers(projectId));
            result.put("profiles", profiles.available().stream().map(profile -> Map.of(
                    "id", profile.id(), "version", profile.version(), "checks", profile.checks().stream().map(IntegrationProfiles.Check::id).toList())).toList());
            return ResponseEntity.ok().header("Cache-Control", "no-store").body(result);
        } catch (SecurityException ex) {
            return ResponseEntity.status(403).header("Cache-Control", "no-store").body(Map.of("error", ex.getMessage()));
        } catch (IllegalStateException | IllegalArgumentException ex) {
            return ResponseEntity.status(409).header("Cache-Control", "no-store").body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/api/v1/workbench/assign")
    public ResponseEntity<?> assign(@RequestHeader(value="Authorization", required=false) String authorization, @RequestBody JsonNode request) {
        return dispatch(authorization, request, true);
    }

    @PostMapping("/api/v1/workbench/claim")
    public ResponseEntity<?> claim(@RequestHeader(value="Authorization", required=false) String authorization, @RequestBody JsonNode request) {
        return dispatch(authorization, request, false);
    }

    private ResponseEntity<?> dispatch(String authorization, JsonNode request, boolean assignment) {
        try {
            var actor = identities.authenticate(authorization);
            if (!actor.projectId().equals(projectId)) throw new SecurityException("Project membership required");
            if (assignment && !actor.roles().contains(ProjectRole.PROJECT_MANAGER)) throw new SecurityException("Required role: PROJECT_MANAGER");
            var allowed = assignment ? Set.of("taskId", "expectedVersion", "idempotencyKey", "assignee") : Set.of("taskId", "expectedVersion", "idempotencyKey");
            if (!request.isObject() || request.properties().stream().anyMatch(field -> !allowed.contains(field.getKey())))
                throw new IllegalArgumentException("Unexpected command fields");
            ObjectNode validation = request.deepCopy(); validation.remove("assignee"); validation.put("taskToken", "server-validation");
            McpToolSchemas.validateInput("claim_task", validation, mapper);
            if (assignment) {
                if (!request.path("assignee").isTextual() || request.path("assignee").asText().isBlank()) throw new IllegalArgumentException("Assignee required");
                identities.requireAssignee(projectId, request.path("assignee").asText());
            }
            Object result = assignment ? journal.operatorCommand(actor, "assign_task", request) : journal.claimAssignedTask(actor, request);
            return ResponseEntity.ok().header("Cache-Control", "no-store").body(result);
        } catch (SecurityException ex) {
            return ResponseEntity.status(403).header("Cache-Control", "no-store").body(Map.of("error", ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().header("Cache-Control", "no-store").body(Map.of("error", ex.getMessage()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(409).header("Cache-Control", "no-store").body(Map.of("error", ex.getMessage()));
        }
    }
}
