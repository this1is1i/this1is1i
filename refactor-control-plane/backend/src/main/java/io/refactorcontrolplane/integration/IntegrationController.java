package io.refactorcontrolplane.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.refactorcontrolplane.governance.LocalIdentityRegistry;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/integrations/candidates")
public class IntegrationController {
    private final IntegrationService service;
    private final LocalIdentityRegistry identities;
    private final ObjectMapper mapper;
    public IntegrationController(IntegrationService service, LocalIdentityRegistry identities, ObjectMapper mapper) {
        this.service = service; this.identities = identities; this.mapper = mapper;
    }
    @GetMapping public Object list(@RequestHeader(value="Authorization", required=false) String auth) {
        return service.list(identities.authenticate(auth));
    }
    @GetMapping("/{id}") public Object get(@RequestHeader(value="Authorization", required=false) String auth, @PathVariable String id) {
        return service.get(identities.authenticate(auth), id);
    }
    @PostMapping public Object create(@RequestHeader(value="Authorization", required=false) String auth, @RequestBody JsonNode body) {
        var actor = identities.authenticate(auth);
        fields(body, Set.of("idempotencyKey", "expectedVersion", "taskIds", "profileId"));
        if (!body.path("expectedVersion").isIntegralNumber() || !body.path("expectedVersion").canConvertToLong()
                || !body.path("taskIds").isArray()) throw new IllegalArgumentException("Invalid version or task list");
        body.path("taskIds").forEach(id -> { if (!id.isTextual() || id.asText().isBlank()) throw new IllegalArgumentException("Invalid task ID"); });
        text(body, "idempotencyKey", "profileId");
        return service.create(actor, mapper.convertValue(body, IntegrationService.Create.class));
    }
    @PostMapping("/{id}/validate") public Object validate(@RequestHeader(value="Authorization", required=false) String auth,
            @PathVariable String id, @RequestBody JsonNode body) {
        var actor = identities.authenticate(auth);
        fields(body, Set.of("idempotencyKey", "expectedFingerprint")); text(body, "idempotencyKey", "expectedFingerprint");
        return service.validate(actor, id, mapper.convertValue(body, IntegrationService.Validate.class));
    }
    @PostMapping("/{id}/proposal") public Object proposal(@RequestHeader(value="Authorization", required=false) String auth,
            @PathVariable String id, @RequestBody JsonNode body) {
        var actor = identities.authenticate(auth);
        fields(body, Set.of("idempotencyKey", "expectedFingerprint", "validationId")); text(body, "idempotencyKey", "expectedFingerprint", "validationId");
        return service.propose(actor, id, mapper.convertValue(body, IntegrationService.Propose.class));
    }
    @PostMapping("/{id}/reconcile-validation") public Object reconcile(@RequestHeader(value="Authorization",required=false) String auth,
            @PathVariable String id,@RequestBody JsonNode body) {
        var actor=identities.authenticate(auth);
        fields(body,Set.of("idempotencyKey","expectedFingerprint","validationId","reason"));
        text(body,"idempotencyKey","expectedFingerprint","validationId","reason");
        return service.reconcileValidation(actor,id,mapper.convertValue(body,IntegrationService.Reconcile.class));
    }
    private void fields(JsonNode body, Set<String> fields) {
        if (!body.isObject()) throw new IllegalArgumentException("Expected an object");
        body.fieldNames().forEachRemaining(field -> { if (!fields.contains(field)) throw new IllegalArgumentException("Unknown field: " + field); });
        for (String field : fields) if (!body.hasNonNull(field)) throw new IllegalArgumentException("Missing field: " + field);
    }
    private void text(JsonNode body, String... fields) {
        for (String field : fields) if (!body.path(field).isTextual() || body.path(field).asText().isBlank())
            throw new IllegalArgumentException("Expected nonblank text: " + field);
    }
    @ExceptionHandler(SecurityException.class) ResponseEntity<?> forbidden(SecurityException ex) { return error(403, ex); }
    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<?> bad(IllegalArgumentException ex) { return error(400, ex); }
    @ExceptionHandler(IllegalStateException.class) ResponseEntity<?> conflict(IllegalStateException ex) { return error(409, ex); }
    private ResponseEntity<?> error(int code, RuntimeException ex) { return ResponseEntity.status(code).body(Map.of("error", ex.getMessage())); }
}
