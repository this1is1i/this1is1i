package io.refactorcontrolplane.project;

import com.fasterxml.jackson.databind.JsonNode;
import io.refactorcontrolplane.governance.LocalIdentityRegistry;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name="control-plane.project.mode", havingValue="configured")
public class ProjectPlanController {
    private final ConfiguredProjectService project;
    private final LocalIdentityRegistry identities;
    private final ProjectRevisions revisions;
    public ProjectPlanController(ConfiguredProjectService project, LocalIdentityRegistry identities, ProjectRevisions revisions) {
        this.project = project; this.identities = identities; this.revisions = revisions;
    }
    @GetMapping("/api/v1/project/plan/revisions")
    public ResponseEntity<?> history(@RequestHeader(value="Authorization",required=false) String auth) {
        return respond(()->revisions.history(identities.authenticate(auth)));
    }
    @PostMapping("/api/v1/project/plan/revisions")
    public ResponseEntity<?> stage(@RequestHeader(value="Authorization",required=false) String auth,@RequestBody JsonNode input) {
        return respond(()-> { fields(input,"expectedFingerprint","definition");
            return revisions.stage(identities.authenticate(auth),text(input,"expectedFingerprint"),input.get("definition")); });
    }
    @GetMapping("/api/v1/project/plan/revisions/{id}")
    public ResponseEntity<?> inspect(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable String id) {
        return respond(()->revisions.inspect(identities.authenticate(auth),id));
    }
    @PostMapping("/api/v1/project/plan/revisions/{id}/decisions")
    public ResponseEntity<?> revisionDecision(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable String id,@RequestBody JsonNode input) {
        return respond(()-> { fields(input,"expectedFingerprint","decision");
            return revisions.decide(identities.authenticate(auth),id,text(input,"expectedFingerprint"),text(input,"decision")); });
    }
    @PostMapping("/api/v1/project/plan/revisions/{id}/activate")
    public ResponseEntity<?> activate(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable String id,@RequestBody JsonNode input) {
        return respond(()-> { fields(input,"expectedFingerprint");
            return revisions.activate(identities.authenticate(auth),id,text(input,"expectedFingerprint")); });
    }
    private static void fields(JsonNode input,String... keys) {
        if (!input.isObject() || input.size()!=keys.length || Arrays.stream(keys).anyMatch(key->!input.hasNonNull(key)))
            throw new IllegalArgumentException("Supply exactly: "+String.join(", ",keys));
    }
    private static String text(JsonNode input,String key) {
        if (!input.path(key).isTextual() || input.path(key).asText().isBlank()) throw new IllegalArgumentException(key+" must be nonblank text");
        return input.path(key).asText();
    }
    @GetMapping("/api/v1/project/plan")
    public ResponseEntity<?> get(@RequestHeader(value="Authorization", required=false) String authorization) {
        return respond(() -> project.state(identities.authenticate(authorization)));
    }
    @PostMapping("/api/v1/project/plan/decisions")
    public ResponseEntity<?> decide(@RequestHeader(value="Authorization", required=false) String authorization, @RequestBody JsonNode input) {
        return respond(() -> {
            var actor = identities.authenticate(authorization);
            if (!input.isObject() || input.size() != 2 || !input.path("expectedFingerprint").isTextual()
                    || !input.path("decision").isTextual()) throw new IllegalArgumentException("Supply expectedFingerprint and decision only");
            return project.decide(actor, input.path("expectedFingerprint").asText(), input.path("decision").asText());
        });
    }
    private ResponseEntity<?> respond(java.util.function.Supplier<Object> action) {
        try { return ResponseEntity.ok().header("Cache-Control", "no-store").body(action.get()); }
        catch (SecurityException ex) { return error(403, ex); }
        catch (IllegalArgumentException ex) { return error(400, ex); }
        catch (IllegalStateException ex) { return error(409, ex); }
    }
    private ResponseEntity<?> error(int code, RuntimeException ex) {
        return ResponseEntity.status(code).header("Cache-Control", "no-store").body(Map.of("error", ex.getMessage()));
    }
}
