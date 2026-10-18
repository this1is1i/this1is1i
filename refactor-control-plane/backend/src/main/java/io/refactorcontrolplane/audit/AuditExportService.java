package io.refactorcontrolplane.audit;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.refactorcontrolplane.executor.*;
import io.refactorcontrolplane.governance.*;
import io.refactorcontrolplane.governance.LocalIdentityRegistry.Principal;
import io.refactorcontrolplane.integration.IntegrationService;
import io.refactorcontrolplane.mcp.McpProjectRuntime;
import io.refactorcontrolplane.project.ConfiguredProjectService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Bounded, read-only export of facts. No raw projection, request, command or process output is published. */
@Service
@ConditionalOnProperty(name="control-plane.project.mode", havingValue="configured")
public class AuditExportService {
    public static final int MAX_RECORDS = 2000, MAX_BYTES = 8 * 1024 * 1024;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ConfiguredProjectService source;
    private final McpProjectRuntime runtime;
    private final EvidenceStore evidence;
    private final IntegrationService integrations;
    private final TransactionTemplate transaction;

    public AuditExportService(JdbcTemplate jdbc, ObjectMapper mapper, ConfiguredProjectService source,
            McpProjectRuntime runtime, EvidenceStore evidence, IntegrationService integrations, PlatformTransactionManager manager) {
        this.jdbc=jdbc; this.json=mapper.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        this.source=source; this.runtime=runtime; this.evidence=evidence; this.integrations=integrations;
        transaction=new TransactionTemplate(manager); transaction.setReadOnly(true);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public byte[] export(Principal actor, String expectedScope, String candidateId) {
        if (!actor.projectId().equals(source.overview().projectId()) || !actor.roles().contains(ProjectRole.AUDITOR))
            throw new SecurityException("Project auditor required");
        if (!source.planScope().equals(expectedScope)) throw new IllegalStateException("Refresh the current plan scope");
        synchronized (runtime) {
            return transaction.execute(status -> build(actor, candidateId));
        }
    }

    private byte[] build(Principal actor, String candidateId) {
        String scope=source.planScope();
        budget(scope);
        var planState=source.state(actor); // Reject activated-but-not-restarted or altered plans, without requiring a healthy baseline.
        var snapshot=runtime.auditSnapshot();
        ObjectNode payload=json.createObjectNode();
        payload.put("observedAt",Instant.now().toString()); payload.put("projectId",actor.projectId());
        payload.put("automaticallyMergeMain",false); payload.put("reconciliationRequired",runtime.auditReconciliationRequired());
        payload.put("historyBoundary","CURRENT_PLAN_SCOPE_ONLY");
        payload.put("claimsBoundary","Historical approvals are facts, not current merge permission. Content hashes are not signatures.");
        ObjectNode plan=payload.putObject("plan"); plan.put("scope",scope); plan.put("status",planState.get("status").toString());
        var definition=source.definition(); plan.put("baseCommit",definition.plan().baseCommit());
        plan.put("targetCommit",definition.pinnedTarget()); plan.put("targetRef",definition.targetRef());
        plan.set("requirements",json.valueToTree(definition.requirements()==null?List.of():definition.requirements()));
        plan.put("requirementMapping",definition.requirements()==null||definition.requirements().isEmpty()?"UNMAPPED":"DECLARED_TASK_LINKS");
        plan.set("planLevelBehavior",json.valueToTree(definition.plan().functionalParity()));
        plan.put("planLevelBehaviorTaskMapping","UNMAPPED");
        plan.set("architectureDecisions",json.valueToTree(definition.architectureDecisions()==null?List.of():definition.architectureDecisions()));
        plan.set("revisionLineage",json.valueToTree(jdbc.queryForList("SELECT fingerprint,parent_fingerprint,architect,architect_at,manager,manager_at,activated_at,created_at FROM project_revision ORDER BY created_at,fingerprint")));
        var tasks=payload.putArray("tasks");
        for (var task:snapshot.project().tasks()) {
            ObjectNode row=tasks.addObject(); row.put("id",task.id()); row.put("status",task.status().name());
            row.put("contextVersion",task.contextVersion()); row.put("contractVersion",task.contractVersion());
            row.put("schemaVersion",task.schemaVersion()); row.put("adrVersion",task.adrVersion()); row.put("baseCommit",task.baseCommit());
            row.put("semanticallyInvalidated",snapshot.project().invalidatedTasks().contains(task.id()));
            row.put("owner",snapshot.assignmentOwners().get(task.id()));
            row.set("gateEvidence",gateReferences(json.valueToTree(snapshot.gateEvidence().getOrDefault(task.id(),Map.of()))));
            row.set("gateResults",json.valueToTree(snapshot.project().gates().getOrDefault(task.id(),Map.of())));
            row.set("dependencies",json.valueToTree(definition.plan().workPackages().stream().filter(t->t.id().equals(task.id())).findFirst().orElseThrow().dependencies()));
        }
        // Reconstruct prior attempts as well as the latest in-memory attempt. Unknown journal entries remain unknown.
        var executions=new TreeMap<String,JsonNode>(); var runs=new TreeMap<String,JsonNode>();
        var referenced=new HashSet<String>();
        var operations=payload.putArray("operations");
        jdbc.query("SELECT journal_sequence,idempotency_key,task_id,event_type,payload,result,status,version,created_at,completed_at FROM audit_event WHERE plan_scope=? ORDER BY journal_sequence", rs -> {
            var input=parse(rs.getString("payload")); var result=parse(rs.getString("result"));
            var row=operations.addObject(); row.put("sequence",rs.getLong("journal_sequence"));
            row.put("operationKey",rs.getString("idempotency_key")); row.put("taskId",rs.getString("task_id"));
            row.put("action",rs.getString("event_type")); row.put("status",rs.getString("status")); row.put("version",rs.getLong("version"));
            row.put("createdAt",rs.getString("created_at")); row.put("completedAt",rs.getString("completed_at"));
            row.put("actor",input.path("actorSubject").asText("UNKNOWN")); row.set("roles",input.path("actorRoles").isArray()?input.path("actorRoles"):json.createArrayNode());
            copy(input,row,"taskExecutionId","commitSha","gate","passed");
            if ("COMPLETED".equals(rs.getString("status")) && result.path("journalVersion").asInt()==2) {
                String taskId=rs.getString("task_id"); var projection=result.path("projection");
                row.set("execution",select(projection.path("executions").path(taskId),"taskId","executionId","branch","baseCommit","commitSha"));
                row.set("gateEvidence",gateReferences(projection.path("gateEvidence").path(taskId)));
                for(var task:projection.path("project").path("tasks"))if(taskId.equals(task.path("id").asText()))
                    row.set("observedTaskState",select(task,"id","status","contextVersion","contractVersion","schemaVersion","adrVersion"));
                for(String key:List.of("evidenceRef","sourceEvidenceRef"))if(result.path("response").has(key))
                    row.set(key,evidenceReference(result.path("response").path(key)));
                for (String key:List.of("evidenceRef","sourceEvidenceRef")) if (row.path(key).isTextual()) referenced.add(row.path(key).asText());
                collect(result.path("projection"),executions,runs,referenced);
            } else row.put("resultInterpretation","NOT_A_COMPLETED_V2_PROJECTION");
        },scope);
        collect(json.valueToTree(snapshot),executions,runs,referenced);
        var attempts=payload.putArray("executions");
        for (var item:executions.values()) attempts.add(select(item,"taskId","executionId","branch","baseCommit","commitSha"));
        var managed=payload.putArray("executorRuns");
        for (var entry:runs.entrySet()) {
            var item=entry.getValue(); var row=managed.addObject(); row.put("executionId",entry.getKey());
            if(!scope.equals(item.path("request").path("planScope").asText()))throw new IllegalStateException("Executor history belongs to another plan");
            copy(item,row,"state","observedAt","modelEvidenceRef");
            row.set("request",select(item.path("request"),"planScope","requestHash","executorId","executorFingerprint","owner","assignmentEpoch","contextVersion"));
            var result=select(item.path("result"),"sequence","state","commitSha");var checks=result.putArray("checks");
            for(var check:item.path("result").path("checks"))checks.add(select(check,"id","exitCode","timedOut","truncated"));
            row.set("result",result);
            if (item.hasNonNull("modelEvidenceRef") || !item.path("result").path("modelRuns").isEmpty()) {
                try { new ExecutionModelEvidence(evidence,json).verify(json.treeToValue(item,ExecutionProtocol.Run.class)); }
                catch (java.io.IOException ex) { throw new IllegalStateException("Invalid executor history",ex); }
                row.put("modelBinding","VERIFIED_EXECUTOR_REPORTED");
            }
        }
        var records=payload.putArray("evidence");
        for (String id:jdbc.queryForList("SELECT id FROM evidence_record WHERE plan_scope=? ORDER BY id",String.class,scope)) {
            var fact=evidence.read(scope,id); var row=records.addObject();
            row.put("id",fact.id()); row.put("operationKey",fact.operationKey()); row.put("kind",fact.kind());
            row.put("author",fact.author()); row.put("createdAt",fact.createdAt()); row.set("binding",json.valueToTree(fact.binding()));
            row.put("integrity","VERIFIED_CONTENT_HASH");
            boolean currentGate=snapshot.gateEvidence().values().stream().anyMatch(refs->refs.containsValue(id));
            boolean currentModel=snapshot.executions().values().stream().map(execution->snapshot.executorRuns().get(execution.executionId()))
                    .filter(Objects::nonNull).anyMatch(run->id.equals(run.modelEvidenceRef()));
            row.put("referenceStatus",currentGate?"CURRENT_GATE_REFERENCE":currentModel?"CURRENT_EXECUTOR_REFERENCE":referenced.contains(id)?"HISTORICAL_RECORDED_REFERENCE":"UNREFERENCED");
            var execution=executions.get(fact.binding().executionId());
            row.put("executionBinding",execution!=null && fact.binding().taskId().equals(execution.path("taskId").asText())
                    && fact.binding().commitSha().equals(execution.path("commitSha").asText())?"TASK_EXECUTION_COMMIT_MATCHED":"UNVERIFIED");
            row.put("semanticBinding","DECLARED_NOT_INDEPENDENTLY_REVERIFIED");
            var content=fact.content();
            row.put("contentHash",hash(encode(canonical(content))));
            row.set("content",switch(fact.kind()) {
                case "CODE_REVIEW","BUSINESS_ACCEPTANCE" -> select(content,"statement","passed");
                case "UNIT_TEST","INTEGRATION_TEST" -> select(content,"candidateId","validationId","validationHash","passed");
                case "EXECUTOR_MODEL_RUNS" -> modelContent(content);
                case "SEMANTIC_SOURCE_CHANGE" -> select(content,"provenance","asset","assetVersion","resourceIds","artifactPaths","affectedTaskIds");
                default -> json.createObjectNode().put("interpretation","UNSUPPORTED_KIND");
            });
        }
        var integrationRows=jdbc.queryForList("SELECT id,kind FROM integration_record WHERE plan_scope=? ORDER BY id",scope);
        var integration=payload.putArray("integrations");
        jdbc.query("SELECT id,kind,actor,payload,revision FROM integration_record WHERE plan_scope=? ORDER BY id",rs -> {
            var item=parse(rs.getString("payload")); var row=integration.addObject();
            row.put("id",rs.getString("id")); row.put("kind",rs.getString("kind")); row.put("actor",rs.getString("actor")); row.put("revision",rs.getLong("revision"));
            switch(rs.getString("kind")) {
                case "candidate" -> {
                    copy(item,row,"state","latestValidation");
                    row.set("plan",select(item.path("plan"),"id","createdAt","fingerprint","targetRef","targetCommit"));
                    row.set("profile",select(item.path("plan").path("profile"),"id","version"));
                    var inputs=row.putArray("inputs");
                    for (var input:item.path("plan").path("inputs")) {
                        var link=inputs.addObject(); copy(input,link,"taskId","owner","contextVersion","contractVersion","schemaVersion","adrVersion");
                        link.set("execution",select(input.path("execution"),"taskId","executionId","branch","baseCommit","commitSha"));
                    }
                    row.set("assembly",select(item.path("assembly"),"commit","conflictingTask","conflictingPaths"));
                }
                case "validation" -> {
                    copy(item,row,"id","candidateId","fingerprint","commitSha","actor","state","startedAt","completedAt");
                    row.put("detailHash",hash(encode(item.path("detail")))); var checks=row.putArray("checks");
                    for (var check:item.path("checks")) {
                        var summary=select(check,"checkId","exitCode","timedOut","truncated");
                        summary.put("commandHash",hash(encode(check.path("argv"))));
                        summary.put("stdoutHash",hash(encode(check.path("stdout")))); summary.put("stderrHash",hash(encode(check.path("stderr")))); checks.add(summary);
                    }
                }
                case "proposal" -> copy(item,row,"id","candidateId","fingerprint","validationId","branch","commitSha","targetRef","targetCommit","actor","createdAt","automaticallyMergeMain","mergePolicy");
                default -> row.put("interpretation","UNSUPPORTED_KIND");
            }
        },scope);
        ObjectNode observed=payload.putObject("selectedCandidate"); observed.put("eligibility","NOT_EVALUATED");
        if (candidateId!=null) {
            if (integrationRows.stream().noneMatch(row->candidateId.equals(row.get("ID")) && "candidate".equals(row.get("KIND"))))
                throw new IllegalArgumentException("Unknown candidate in this plan");
            observed.put("id",candidateId);
            observed.put("eligibility",integrations.auditProposalEligibility(actor,candidateId)?"ELIGIBLE_AT_OBSERVATION":"NOT_ELIGIBLE_AT_OBSERVATION");
        }
        ObjectNode report=json.createObjectNode(); report.put("schemaVersion","refactor-control-plane.audit.v1");
        JsonNode canonicalPayload=canonical(payload); report.set("payload",canonicalPayload);
        report.putObject("integrity").put("algorithm","SHA-256").put("canonicalization","SORTED_OBJECT_KEYS_UTF8_COMPACT_JSON")
                .put("payloadSha256",hash(encode(canonicalPayload))).put("signature",false);
        byte[] bytes=encode(report); if (bytes.length>MAX_BYTES) throw new ExportLimitException(); return bytes;
    }

    private void budget(String scope) {
        long count=0,chars=0;
        for (String table:List.of("audit_event","integration_record","evidence_record")) {
            count+=jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE plan_scope=?",Long.class,scope);
            String expression=table.equals("audit_event")?"CHAR_LENGTH(payload)+COALESCE(CHAR_LENGTH(result),0)":table.equals("integration_record")?"CHAR_LENGTH(request_json)+CHAR_LENGTH(payload)":"CHAR_LENGTH(payload)";
            chars+=jdbc.queryForObject("SELECT COALESCE(SUM("+expression+"),0) FROM "+table+" WHERE plan_scope=?",Long.class,scope);
        }
        count+=jdbc.queryForObject("SELECT COUNT(*) FROM project_revision",Long.class);
        if (count>MAX_RECORDS || chars>MAX_BYTES) throw new ExportLimitException();
    }
    private void collect(JsonNode projection, Map<String,JsonNode> executions, Map<String,JsonNode> runs, Set<String> referenced) {
        projection.path("executions").forEach(item->{ if(item.hasNonNull("executionId")) executions.put(item.path("executionId").asText(),item); });
        projection.path("executorRuns").forEach(item->{ if(item.path("request").path("execution").hasNonNull("executionId")) runs.put(item.path("request").path("execution").path("executionId").asText(),item); });
        projection.path("gateEvidence").forEach(refs->refs.forEach(ref->{if(ref.isTextual())referenced.add(ref.asText());}));
        projection.path("executorRuns").forEach(run->{if(run.hasNonNull("modelEvidenceRef"))referenced.add(run.path("modelEvidenceRef").asText());});
    }
    private JsonNode parse(String text) {
        if(text==null)return NullNode.instance;
        try{return json.readTree(text);}catch(java.io.IOException ex){throw new IllegalStateException("Invalid audit history",ex);}
    }
    private ObjectNode select(JsonNode from,String...fields){var result=json.createObjectNode();copy(from,result,fields);return result;}
    private ObjectNode gateReferences(JsonNode refs) {
        var result=json.createObjectNode();
        for(var gate:QualityGate.values())if(refs.has(gate.name()))result.set(gate.name(),evidenceReference(refs.path(gate.name())));
        return result;
    }
    private JsonNode evidenceReference(JsonNode ref) {
        if(ref.isTextual() && ref.asText().matches("[a-f0-9]{64}"))return ref;
        return json.createObjectNode().put("interpretation","LEGACY_UNVERIFIED").put("referenceHash",hash(encode(canonical(ref))));
    }
    private ObjectNode modelContent(JsonNode content) {
        var result=select(content,"provenance","requestHash","executorFingerprint","owner","assignmentEpoch");
        var models=result.putArray("modelRuns");
        for(var item:content.path("modelRuns")) {
            var model=select(item,"id","model","promptVersion","inputHash","contextHash","outputHash","totalTokens","requestedTools");
            var tools=model.putArray("toolCalls");for(var tool:item.path("toolCalls"))tools.add(select(tool,"id","name","inputHash","outputHash","state"));models.add(model);
        }
        return result;
    }
    private void copy(JsonNode from,ObjectNode to,String...fields){for(String field:fields)if(from.has(field))to.set(field,from.get(field));}
    private JsonNode canonical(JsonNode node){
        if(node.isObject()){var result=json.createObjectNode();var names=new TreeSet<String>();node.fieldNames().forEachRemaining(names::add);for(String name:names)result.set(name,canonical(node.get(name)));return result;}
        if(node.isArray()){var result=json.createArrayNode();node.forEach(item->result.add(canonical(item)));return result;}return node.deepCopy();
    }
    private byte[] encode(JsonNode node){try{return json.writeValueAsBytes(node);}catch(java.io.IOException ex){throw new IllegalStateException("Cannot encode audit report",ex);}}
    private String hash(byte[] bytes){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}}
    public static class ExportLimitException extends IllegalStateException { public ExportLimitException(){super("Audit export exceeds the bounded report budget");} }
}
