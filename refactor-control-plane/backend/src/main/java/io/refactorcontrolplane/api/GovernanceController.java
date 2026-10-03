package io.refactorcontrolplane.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.refactorcontrolplane.governance.LocalIdentityRegistry;
import io.refactorcontrolplane.mcp.McpProjectRuntime;
import io.refactorcontrolplane.mcp.ProjectMcpController;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class GovernanceController {
    private final LocalIdentityRegistry identities;
    private final McpProjectRuntime runtime;
    private final ProjectMcpController journal;
    private final ObjectMapper mapper;

    public GovernanceController(LocalIdentityRegistry identities, McpProjectRuntime runtime,
            ProjectMcpController journal, ObjectMapper mapper) {
        this.identities = identities; this.runtime = runtime; this.journal = journal; this.mapper = mapper;
    }

    @PostMapping("/api/v1/governance/commands")
    public ResponseEntity<?> command(@RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody JsonNode request) {
        try {
            var principal = identities.authenticate(authorization);
            if (!request.isObject()) throw new IllegalArgumentException("Command must be an object");
            String action = request.path("action").asText();
            if (action.equals("assign_task")) identities.requireAssignee(principal.projectId(), request.path("assignee").asText());
            synchronized (runtime) {
                Object result = journal.operatorCommand(principal, action, request);
                ObjectNode response = mapper.valueToTree(result);
                if (action.equals("assign_task")) {
                    response.put("taskToken", runtime.assignmentToken(request.path("taskId").asText()));
                }
                return ResponseEntity.ok(response);
            }
        } catch (SecurityException exception) {
            return ResponseEntity.status(403).body(Map.of("error", exception.getMessage()));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
        } catch (IllegalStateException | io.refactorcontrolplane.governance.GateNotSatisfiedException exception) {
            return ResponseEntity.status(409).body(Map.of("error", exception.getMessage()));
        }
    }
}
