package io.refactorcontrolplane.api;

import io.refactorcontrolplane.audit.AuditExportService;
import io.refactorcontrolplane.governance.LocalIdentityRegistry;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name="control-plane.project.mode", havingValue="configured")
public class AuditExportController {
    private final LocalIdentityRegistry identities;
    private final AuditExportService exports;
    public AuditExportController(LocalIdentityRegistry identities,AuditExportService exports){this.identities=identities;this.exports=exports;}
    @GetMapping("/api/v1/audit/export")
    public ResponseEntity<?> download(@RequestHeader(value="Authorization",required=false)String authorization,@RequestParam Map<String,String> query){
        try {
            var actor=identities.authenticate(authorization);
            if(query.keySet().stream().anyMatch(key->!Set.of("expectedPlanScope","candidateId").contains(key)))throw new IllegalArgumentException();
            byte[] bytes=exports.export(actor,query.get("expectedPlanScope"),query.get("candidateId"));
            return ResponseEntity.ok().header("Cache-Control","no-store").header("X-Content-Type-Options","nosniff")
                    .header("Content-Disposition","attachment; filename=\"audit-"+query.get("expectedPlanScope")+".json\"")
                    .contentType(MediaType.APPLICATION_JSON).body(bytes);
        } catch(SecurityException ex){return error(403,"Project auditor required");}
        catch(AuditExportService.ExportLimitException ex){return error(413,"Audit export exceeds the report budget; no partial report was produced");}
        catch(IllegalArgumentException ex){return error(400,"Invalid audit export request");}
        catch(IllegalStateException ex){return error(409,"Audit export could not verify a consistent current plan and evidence; refresh or reconcile");}
    }
    private ResponseEntity<?> error(int status,String message){return ResponseEntity.status(status).header("Cache-Control","no-store").body(Map.of("error",message));}
}
