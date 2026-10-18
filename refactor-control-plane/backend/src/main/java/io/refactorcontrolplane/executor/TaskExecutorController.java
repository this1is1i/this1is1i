package io.refactorcontrolplane.executor;

import com.fasterxml.jackson.databind.JsonNode;
import io.refactorcontrolplane.governance.LocalIdentityRegistry;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class TaskExecutorController {
    private final ExecutorCoordinator coordinator;
    private final LocalIdentityRegistry identities;
    public TaskExecutorController(ExecutorCoordinator coordinator, LocalIdentityRegistry identities) {
        this.coordinator = coordinator; this.identities = identities;
    }
    @GetMapping("/executors") public ResponseEntity<?> available(@RequestHeader(value="Authorization", required=false) String auth) {
        return respond(() -> coordinator.available(identities.authenticate(auth)));
    }
    @GetMapping("/executions/{id}") public ResponseEntity<?> read(@RequestHeader(value="Authorization", required=false) String auth, @PathVariable String id) {
        return respond(() -> coordinator.read(identities.authenticate(auth), id));
    }
    @PostMapping("/executions/{id}/dispatch") public ResponseEntity<?> dispatch(@RequestHeader(value="Authorization", required=false) String auth,
            @PathVariable String id, @RequestBody JsonNode body) {
        return respond(() -> {
            var actor = identities.authenticate(auth);
            if (!body.isObject() || body.size() != 4 || !body.path("expectedVersion").isIntegralNumber()
                    || !body.path("expectedVersion").canConvertToLong() || body.path("expectedVersion").asLong() < 1)
                throw new IllegalArgumentException("Dispatch requires taskId, executorId, idempotencyKey and a positive integer expectedVersion");
            for (String name : List.of("taskId", "executorId", "idempotencyKey")) if (!body.path(name).isTextual()
                    || body.path(name).asText().isBlank() || body.path(name).asText().length() > 200)
                throw new IllegalArgumentException("Invalid dispatch field: " + name);
            return coordinator.dispatch(actor, id, body.path("taskId").asText(), body.path("executorId").asText(),
                    body.path("expectedVersion").asLong(), body.path("idempotencyKey").asText());
        });
    }
    @PostMapping("/executions/{id}/refresh") public ResponseEntity<?> refresh(@RequestHeader(value="Authorization", required=false) String auth, @PathVariable String id) {
        return respond(() -> coordinator.refresh(identities.authenticate(auth), id));
    }
    private ResponseEntity<?> respond(Supplier<Object> action) {
        try { return ResponseEntity.ok(action.get()); }
        catch (SecurityException ex) { return ResponseEntity.status(403).body(Map.of("error", ex.getMessage())); }
        catch (IllegalArgumentException ex) { return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage())); }
        catch (IllegalStateException ex) { return ResponseEntity.status(409).body(Map.of("error", ex.getMessage())); }
    }
}
