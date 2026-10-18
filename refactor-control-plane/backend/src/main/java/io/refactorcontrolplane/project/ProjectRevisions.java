package io.refactorcontrolplane.project;

import com.fasterxml.jackson.databind.JsonNode;
import io.refactorcontrolplane.analysis.*;
import io.refactorcontrolplane.governance.LocalIdentityRegistry.Principal;
import io.refactorcontrolplane.governance.ProjectRole;
import io.refactorcontrolplane.integration.IntegrationService;
import io.refactorcontrolplane.mcp.McpProjectRuntime;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Immutable staged definitions. Activation changes one SQL transaction and requires process restart. */
@Service
@ConditionalOnProperty(name="control-plane.project.mode",havingValue="configured")
public class ProjectRevisions {
    private final ConfiguredProjectService source;
    private final JdbcTemplate jdbc;
    private final McpProjectRuntime runtime;
    private final IntegrationService integration;
    public ProjectRevisions(ConfiguredProjectService source, JdbcTemplate jdbc, McpProjectRuntime runtime, IntegrationService integration) {
        this.source=source; this.jdbc=jdbc; this.runtime=runtime; this.integration=integration;
    }
    public record Revision(String fingerprint,String parentFingerprint,JsonNode definition,String architect,String manager,boolean activated) {}
    public Map<String,Object> history(Principal actor) {
        source.authorize(actor,null);
        return Map.of("activeFingerprint",active(),"runtimeFingerprint",source.planScope(),"subjectId",actor.subjectId(),"roles",actor.roles(),"revisions",
                jdbc.query("SELECT fingerprint FROM project_revision ORDER BY created_at,fingerprint",(rs,n)->read(rs.getString(1))));
    }
    public Map<String,Object> inspect(Principal actor,String id) {
        source.authorize(actor,null); var revision=read(id); var definition=parse(revision.definition());
        return Map.of("revision",revision,"analysis",new LegacyProjectAnalyzer().analyze(GitAnalysisSnapshot.capture(source.repository(),definition.plan().baseCommit())));
    }
    public Revision stage(Principal actor,String expected,JsonNode input) {
        source.authorize(actor,ProjectRole.ARCHITECT);
        return integration.withPlanLock(()-> {
            requireActive(expected); source.requireExecutable();
            String json=canonical(input); var definition=parse(input); validate(definition);
            String fp=ConfiguredProjectService.digest(json);
            if (fp.equals(expected)) throw new IllegalArgumentException("Revision must change the plan");
            var exists=jdbc.queryForList("SELECT fingerprint FROM project_revision WHERE fingerprint=?",String.class,fp);
            if (exists.isEmpty()) jdbc.update("INSERT INTO project_revision(fingerprint,parent_fingerprint,definition_json) VALUES(?,?,?)",fp,expected,json);
            Revision revision=read(fp);
            if (!expected.equals(revision.parentFingerprint())) throw new IllegalStateException("Revision belongs to another parent");
            return revision;
        });
    }
    public Revision decide(Principal actor,String id,String expected,String decision) {
        String column;
        if ("ARCHITECTURE".equals(decision)) { source.authorize(actor,ProjectRole.ARCHITECT); column="architect"; }
        else if ("EXECUTION".equals(decision)) { source.authorize(actor,ProjectRole.PROJECT_MANAGER); column="manager"; }
        else throw new IllegalArgumentException("Unknown decision");
        return integration.withPlanLock(()-> {
            if (!id.equals(expected)) throw new IllegalStateException("Revision fingerprint changed");
            Revision revision=read(id); requireActive(revision.parentFingerprint()); validate(parse(revision.definition()));
            if (column.equals("manager") && revision.architect()==null) throw new IllegalStateException("Architecture confirmation is required first");
            String other=column.equals("architect")?revision.manager():revision.architect();
            if (actor.subjectId().equals(other)) throw new SecurityException("Independent decision subjects required");
            String prior=column.equals("architect")?revision.architect():revision.manager();
            if (prior!=null && !prior.equals(actor.subjectId())) throw new IllegalStateException("Decision already recorded");
            if (prior==null && jdbc.update("UPDATE project_revision SET "+column+"=?, "+column+"_at=CURRENT_TIMESTAMP WHERE fingerprint=? AND "+column+" IS NULL",actor.subjectId(),id)!=1)
                throw new IllegalStateException("Concurrent decision; refresh");
            return read(id);
        });
    }
    public Map<String,Object> activate(Principal actor,String id,String expected) {
        source.authorize(actor,ProjectRole.PROJECT_MANAGER);
        return integration.withPlanLock(()->source.transactions.execute(status->{
            Revision revision=read(id);
            if (!Objects.equals(expected,revision.parentFingerprint())) throw new IllegalStateException("Parent fingerprint changed");
            if (active().equals(id) && revision.activated()) return activation(id);
            requireActive(expected); source.requireExecutable();
            if (revision.architect()==null || revision.manager()==null) throw new IllegalStateException("Both new approvals required");
            validate(parse(revision.definition())); runtime.requirePlanSwitchSafe();
            if (jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE plan_scope=? AND status='PENDING'",Long.class,expected)>0)
                throw new IllegalStateException("Pending journal must be reconciled");
            // The integration lock waits for in-process validation. Unknown attempts after a crash are explicit blockers.
            integration.requireNoUnknownLatestValidation();
            if (jdbc.update("UPDATE project_plan SET fingerprint=?,definition_json=?,architect=?,architect_at=(SELECT architect_at FROM project_revision WHERE fingerprint=?),manager=?,manager_at=(SELECT manager_at FROM project_revision WHERE fingerprint=?) WHERE slot=1 AND fingerprint=?",
                    id,canonical(revision.definition()),revision.architect(),id,revision.manager(),id,expected)!=1)
                throw new IllegalStateException("Concurrent activation; refresh");
            jdbc.update("UPDATE project_revision SET activated_at=CURRENT_TIMESTAMP WHERE fingerprint=?",id);
            return activation(id);
        }));
    }
    private Map<String,Object> activation(String id) { return Map.of("activeFingerprint",id,"restartRequired",!source.planScope().equals(id)); }
    private String active() { return jdbc.queryForObject("SELECT fingerprint FROM project_plan WHERE slot=1",String.class); }
    private void requireActive(String expected) {
        if (!Objects.equals(expected,active()) || !Objects.equals(expected,source.planScope())) throw new IllegalStateException("Active plan changed; refresh or restart");
    }
    private void validate(ProjectDefinition definition) {
        if (!source.definition().projectId().equals(definition.projectId()) || !source.repository().toString().equals(definition.repository())
                || !source.definition().targetRef().equals(definition.targetRef()) || !source.definition().pinnedTarget().equals(definition.pinnedTarget()))
            throw new IllegalArgumentException("Revision must preserve project, repository and pinned target");
        ProjectPlanValidator.validate(definition,source.repository());
        if (!definition.plan().baseCommit().equals(source.definition().plan().baseCommit())) {
            if (definition.baselineCandidateId()==null) throw new IllegalArgumentException("Changed baseline requires accepted candidate provenance");
            integration.requireBaselineCandidate(definition.baselineCandidateId(),definition.plan().baseCommit(),definition.pinnedTarget());
        } else if (!Objects.equals(definition.baselineCandidateId(),source.definition().baselineCandidateId()))
            throw new IllegalArgumentException("Unchanged baseline must preserve candidate provenance");
    }
    private Revision read(String fp) {
        return jdbc.query("SELECT * FROM project_revision WHERE fingerprint=?",(rs,n)-> {
            try {
                String json=rs.getString("definition_json");
                if (!ConfiguredProjectService.digest(json).equals(fp)) throw new IllegalStateException("Revision content was altered");
                return new Revision(fp,rs.getString("parent_fingerprint"),source.reader.readTree(json),rs.getString("architect"),rs.getString("manager"),rs.getTimestamp("activated_at")!=null);
            } catch (java.io.IOException ex) { throw new IllegalStateException("Invalid persisted revision",ex); }
        },fp).stream().findFirst().orElseThrow(()->new IllegalArgumentException("Unknown revision"));
    }
    private ProjectDefinition parse(JsonNode input) {
        ProjectScheduling.validateJson(input);
        try { return source.reader.treeToValue(input,ProjectDefinition.class); }
        catch (java.io.IOException ex) { throw new IllegalArgumentException("Invalid project definition",ex); }
    }
    private String canonical(JsonNode input) {
        try {
            String value=source.reader.writeValueAsString(ConfiguredProjectService.canonical(input,source.reader));
            if (value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>262144) throw new IllegalArgumentException("Definition exceeds 256 KiB");
            return value;
        } catch (java.io.IOException ex) { throw new IllegalArgumentException("Invalid definition",ex); }
    }
}
