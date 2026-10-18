package io.refactorcontrolplane.api;

import io.refactorcontrolplane.governance.*;
import io.refactorcontrolplane.project.ProjectSource;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class EvidenceController {
    private final EvidenceStore store;
    private final ProjectSource source;
    private final LocalIdentityRegistry identities;
    public EvidenceController(EvidenceStore store,ProjectSource source,LocalIdentityRegistry identities) {
        this.store=store; this.source=source; this.identities=identities;
    }
    @GetMapping("/api/v1/evidence/{id}") public ResponseEntity<?> read(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable String id) {
        try {
            var actor=identities.authenticate(auth);
            if (!actor.projectId().equals(source.overview().projectId())) throw new SecurityException("Project membership required");
            return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(store.read(source.planScope(),id));
        } catch(SecurityException ex) { return ResponseEntity.status(403).body(Map.of("error",ex.getMessage())); }
        catch(IllegalArgumentException ex) { return ResponseEntity.badRequest().body(Map.of("error",ex.getMessage())); }
        catch(IllegalStateException ex) { return ResponseEntity.status(409).body(Map.of("error",ex.getMessage())); }
    }
}
