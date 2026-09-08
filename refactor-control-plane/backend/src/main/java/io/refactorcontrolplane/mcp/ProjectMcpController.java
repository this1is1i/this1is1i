package io.refactorcontrolplane.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.refactorcontrolplane.api.DemoProjectOverview;
import io.refactorcontrolplane.api.DemoProjectService;
import io.refactorcontrolplane.persistence.AuditEventRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProjectMcpController {

    private static final List<String> TOOL_NAMES = List.of(
            "get_project_context", "get_ready_tasks", "claim_task", "heartbeat", "get_task_package",
            "reserve_paths", "release_paths", "publish_contract_change", "publish_schema_change",
            "record_architecture_decision", "report_change_event", "submit_changeset", "report_blocker",
            "get_dependency_impact", "get_integration_status");
    private static final Set<String> WRITE_TOOLS = Set.of(
            "claim_task", "heartbeat", "reserve_paths", "release_paths", "publish_contract_change",
            "publish_schema_change", "record_architecture_decision", "report_change_event",
            "submit_changeset", "report_blocker");

    private final DemoProjectService projectService;
    private final McpProjectRuntime runtime;
    private final AuditEventRepository auditEvents;
    private final ObjectMapper mapper;

    public ProjectMcpController(
            DemoProjectService projectService, McpProjectRuntime runtime, AuditEventRepository auditEvents,
            ObjectMapper mapper) {
        this.projectService = projectService;
        this.runtime = runtime;
        this.auditEvents = auditEvents;
        this.mapper = mapper;
    }

    @PostMapping("/mcp")
    public ResponseEntity<?> handle(@RequestBody McpRequest request) {
        if (request.id() == null) return ResponseEntity.accepted().build();
        if (!"2.0".equals(request.jsonrpc())) {
            return ResponseEntity.ok(error(request.id(), -32600, "Invalid JSON-RPC version"));
        }
        Map<String, Object> response = switch (request.method() == null ? "" : request.method()) {
            case "initialize" -> success(request.id(), Map.of(
                    "protocolVersion", "2025-03-26",
                    "capabilities", Map.of("tools", Map.of("listChanged", false)),
                    "serverInfo", Map.of("name", "refactor-control-plane", "version", "0.1.0")));
            case "tools/list" -> success(request.id(), Map.of("tools", TOOL_NAMES.stream()
                    .map(this::toolDescriptor).toList()));
            case "tools/call" -> callTool(request);
            default -> error(request.id(), -32601, "Method not found: " + request.method());
        };
        return ResponseEntity.ok(response);
    }

    private Map<String, Object> callTool(McpRequest request) {
        JsonNode params = request.params();
        if (params == null || !params.isObject()) {
            return error(request.id(), -32602, "tools/call requires object params");
        }
        String name = params.path("name").asText();
        if (!TOOL_NAMES.contains(name)) return error(request.id(), -32602, "Unknown tool: " + name);
        JsonNode arguments = params.path("arguments");
        if (WRITE_TOOLS.contains(name) && !validWriteEnvelope(arguments)) {
            return error(request.id(), -32001,
                    "Write tools require taskToken, idempotencyKey and positive expectedVersion");
        }

        try {
            Object structured = execute(name, arguments);
            return success(request.id(), Map.of(
                    "content", List.of(Map.of("type", "text", "text", "Tool " + name + " completed")),
                    "structuredContent", structured));
        } catch (SecurityException exception) {
            return error(request.id(), -32002, exception.getMessage());
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return error(request.id(), -32003, exception.getMessage());
        }
    }

    /*
     * The v0.1 runtime is intentionally single-node. Serializing record -> domain write ->
     * completion makes the database-generated journal sequence the actual execution order.
     */
    private synchronized Object execute(String name, JsonNode arguments) {
        DemoProjectOverview overview = projectService.overview();
        return switch (name) {
            case "get_project_context" -> overview;
            case "get_ready_tasks" -> Map.of("tasks", runtime.readyTasks(),
                    "contextVersion", runtime.contextVersion());
            case "get_task_package" -> runtime.task(arguments.path("taskId").asText());
            case "get_dependency_impact" -> Map.of("criticalPath", overview.plan().criticalPath());
            case "get_integration_status" -> runtime.integrationStatus();
            default -> {
                runtime.validateToken(arguments);
                String key = arguments.path("idempotencyKey").asText();
                String taskId = arguments.path("taskId").asText("unknown-task");
                long expectedVersion = arguments.path("expectedVersion").asLong();
                String payload = auditPayload(name, arguments);
                var existing = auditEvents.findByIdempotencyKey(key);
                if (existing.isPresent()) {
                    var event = existing.get();
                    if (!event.taskId().equals(taskId) || !event.eventType().equals(name)
                            || !event.payload().equals(payload)) {
                        throw new IllegalStateException("Idempotency key was already used for another operation");
                    }
                    if (!event.status().equals("COMPLETED")) {
                        throw new IllegalStateException("Previous operation has not completed; reconcile before retry");
                    }
                    if (event.result() == null) {
                        throw new IllegalStateException("Completed operation has no persisted result");
                    }
                    yield readJson(event.result());
                }
                if (!auditEvents.record(key, taskId, name, payload, expectedVersion)) {
                    throw new IllegalStateException("Concurrent idempotency conflict");
                }
                Object result;
                try {
                    result = runtime.execute(name, arguments);
                } catch (RuntimeException exception) {
                    auditEvents.fail(key, expectedVersion);
                    throw exception;
                }
                if (!auditEvents.complete(key, expectedVersion, writeJson(result))) {
                    throw new IllegalStateException("Audit operation version changed before completion");
                }
                yield result;
            }
        };
    }

    private String auditPayload(String toolName, JsonNode arguments) {
        Map<String, JsonNode> payload = new TreeMap<>();
        payload.put("tool", mapper.getNodeFactory().textNode(toolName));
        arguments.properties().forEach(field -> {
            if (!field.getKey().equals("taskToken")) payload.put(field.getKey(), field.getValue());
        });
        return writeJson(payload);
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Cannot persist operation JSON", exception);
        }
    }

    private JsonNode readJson(String value) {
        try {
            return mapper.readTree(value);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Cannot read persisted operation result", exception);
        }
    }

    private boolean validWriteEnvelope(JsonNode arguments) {
        return arguments != null
                && !arguments.path("taskToken").asText().isBlank()
                && !arguments.path("idempotencyKey").asText().isBlank()
                && arguments.path("expectedVersion").asLong(0) > 0;
    }

    private Map<String, Object> toolDescriptor(String name) {
        return Map.of(
                "name", name,
                "description", "Refactor Control Plane project tool: " + name,
                "inputSchema", Map.of("type", "object", "additionalProperties", true));
    }

    private static Map<String, Object> success(JsonNode id, Object result) {
        return Map.of("jsonrpc", "2.0", "id", id, "result", result);
    }

    private static Map<String, Object> error(JsonNode id, int code, String message) {
        return Map.of("jsonrpc", "2.0", "id", id, "error", Map.of("code", code, "message", message));
    }
}
