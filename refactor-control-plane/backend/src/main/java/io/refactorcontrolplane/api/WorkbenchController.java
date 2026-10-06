package io.refactorcontrolplane.api;

import io.refactorcontrolplane.governance.LocalIdentityRegistry;
import io.refactorcontrolplane.integration.IntegrationProfiles;
import io.refactorcontrolplane.mcp.McpProjectRuntime;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class WorkbenchController {
    private final LocalIdentityRegistry identities;
    private final McpProjectRuntime runtime;
    private final IntegrationProfiles profiles;
    private final String projectId;
    public WorkbenchController(LocalIdentityRegistry identities, McpProjectRuntime runtime,
            IntegrationProfiles profiles, DemoProjectService project) {
        this.identities = identities; this.runtime = runtime; this.profiles = profiles;
        this.projectId = project.overview().projectId();
    }
    @GetMapping("/api/v1/workbench")
    public ResponseEntity<?> snapshot(@RequestHeader(value="Authorization", required=false) String authorization) {
        try {
            var actor = identities.authenticate(authorization);
            if (!actor.projectId().equals(projectId)) throw new SecurityException("Project membership required");
            var result = new LinkedHashMap<String, Object>(runtime.workbenchSnapshot());
            result.put("projectId", projectId); result.put("subjectId", actor.subjectId());
            result.put("roles", actor.roles().stream().map(Enum::name).sorted().toList());
            result.put("profiles", profiles.available().stream().map(profile -> Map.of(
                    "id", profile.id(), "version", profile.version(), "checks", profile.checks().stream().map(IntegrationProfiles.Check::id).toList())).toList());
            return ResponseEntity.ok().header("Cache-Control", "no-store").body(result);
        } catch (SecurityException ex) {
            return ResponseEntity.status(403).header("Cache-Control", "no-store").body(Map.of("error", ex.getMessage()));
        } catch (IllegalStateException | IllegalArgumentException ex) {
            return ResponseEntity.status(409).header("Cache-Control", "no-store").body(Map.of("error", ex.getMessage()));
        }
    }
}
