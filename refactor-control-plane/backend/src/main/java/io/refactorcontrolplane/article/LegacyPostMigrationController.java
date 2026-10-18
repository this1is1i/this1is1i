package io.refactorcontrolplane.article;

import com.fasterxml.jackson.databind.JsonNode;
import io.refactorcontrolplane.project.ProjectSource;
import io.refactorcontrolplane.governance.LocalIdentityRegistry;
import io.refactorcontrolplane.governance.ProjectRole;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="control-plane.project.mode",havingValue="demo",matchIfMissing=true)
@RequestMapping("/api/v1/article-migration")
public class LegacyPostMigrationController {
    private final LegacyPostMigrationService migration;
    private final LocalIdentityRegistry identities;
    private final ProjectSource project;
    public LegacyPostMigrationController(LegacyPostMigrationService migration, LocalIdentityRegistry identities, ProjectSource project) {
        this.migration = migration; this.identities = identities; this.project = project;
    }

    @PostMapping({"/preview", "/apply"})
    public ResponseEntity<?> migrate(@RequestHeader(value="Authorization", required=false) String authorization,
            @RequestBody JsonNode request, jakarta.servlet.http.HttpServletRequest http) {
        try {
            var principal = identities.authenticate(authorization);
            if (!principal.projectId().equals(project.overview().projectId()) || !principal.roles().contains(ProjectRole.PROJECT_MANAGER))
                throw new SecurityException("Project manager role required");
            boolean apply = http.getRequestURI().endsWith("/apply");
            Set<String> allowed = apply ? Set.of("sourceZone", "targetStatus", "expectedFingerprint") : Set.of("sourceZone", "targetStatus");
            if (!request.isObject() || request.properties().stream().anyMatch(field -> !allowed.contains(field.getKey())))
                throw new IllegalArgumentException("Unknown migration argument");
            var policy = new LegacyPostMigrationService.Policy(ZoneId.of(text(request, "sourceZone")), ArticleStatus.valueOf(text(request, "targetStatus")));
            return ResponseEntity.ok(apply ? migration.apply(policy, text(request, "expectedFingerprint"), principal.subjectId()) : migration.preview(policy));
        } catch (SecurityException exception) {
            return ResponseEntity.status(403).body(Map.of("error", exception.getMessage()));
        } catch (IllegalArgumentException | java.time.DateTimeException exception) {
            return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
        } catch (IllegalStateException exception) {
            return ResponseEntity.status(409).body(Map.of("error", exception.getMessage()));
        } catch (DataAccessException exception) {
            return ResponseEntity.status(409).body(Map.of("error", "Database migration unavailable or conflicted. Check the local snapshot, import mappings and audit before retrying."));
        }
    }
    private static String text(JsonNode request, String name) {
        JsonNode value = request.path(name);
        if (!value.isTextual() || value.textValue().isBlank()) throw new IllegalArgumentException(name + " must be explicit nonblank text");
        return value.textValue();
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<?> malformedJson() {
        return ResponseEntity.badRequest().body(Map.of("error", "Request must contain a valid JSON object"));
    }
}
