package io.refactorcontrolplane.api;

import static io.refactorcontrolplane.api.ConfiguredProjectApiContractTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties={"control-plane.project.mode=configured", "spring.datasource.url=jdbc:h2:mem:audit-export", "control-plane.executors.auto-poll=false"})
@AutoConfigureMockMvc @DirtiesContext
class AuditExportApiContractTest {
    @TempDir static Path directory;
    static Path repo; static String base;
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    @Autowired MockMvc mvc;
    @Autowired io.refactorcontrolplane.project.ConfiguredProjectService source;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired io.refactorcontrolplane.governance.EvidenceStore store;
    @Autowired io.refactorcontrolplane.mcp.McpProjectRuntime runtime;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) throws Exception {
        repo = Files.createDirectories(directory.resolve("source")); git(repo, "init", "-b", "main"); git(repo, "config", "core.autocrlf", "false");
        Files.createDirectory(repo.resolve("src")); Files.writeString(repo.resolve("src/pom.xml"), "<project/>", StandardCharsets.UTF_8);
        git(repo, "add", "."); commit(repo); base = git(repo, "rev-parse", "HEAD");
        Path file = directory.resolve("project.json"); Files.writeString(file, configuration(repo, base, "src").toString(), StandardCharsets.UTF_8);
        var accounts = JSON.createArrayNode();
        for (var account : List.of(Map.of("subjectId", "auditor", "projectId", "legacy-blog", "roles", List.of("AUDITOR"), "tokenSha256", hash("audit-secret")),
                Map.of("subjectId", "developer", "projectId", "legacy-blog", "roles", List.of("DEVELOPER"), "tokenSha256", hash("developer-secret")),
                Map.of("subjectId", "foreign", "projectId", "another-project", "roles", List.of("AUDITOR"), "tokenSha256", hash("foreign-secret")))) accounts.add(JSON.valueToTree(account));
        Path identities = directory.resolve("identities.json"); Files.writeString(identities, accounts.toString(), StandardCharsets.UTF_8);
        properties.add("control-plane.project.definition-file", file::toString); properties.add("control-plane.identity-file", identities::toString);
    }
    @Test void onlyProjectAuditorsDownloadVersionedScopedReadOnlyReports() throws Exception {
        String url = "/api/v1/audit/export?expectedPlanScope=" + source.planScope();
        mvc.perform(get(url)).andExpect(status().isForbidden()).andExpect(header().string("Cache-Control", "no-store"));
        for (String token : List.of("developer-secret", "foreign-secret"))
            mvc.perform(get(url).header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        String text = mvc.perform(get(url).header("Authorization", "Bearer audit-secret"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(jsonPath("$.schemaVersion").value("refactor-control-plane.audit.v1"))
                .andExpect(jsonPath("$.payload.plan.scope").value(source.planScope()))
                .andExpect(jsonPath("$.payload.automaticallyMergeMain").value(false))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(text.contains("audit-secret")); assertFalse(text.contains(repo.toString().replace("\\", "\\\\")));
        mvc.perform(get("/api/v1/audit/export?expectedPlanScope=" + "f".repeat(64)).header("Authorization", "Bearer audit-secret")).andExpect(status().isConflict());
        assertEquals(base, git(repo, "rev-parse", "main"));
    }
    static String hash(String value) { return io.refactorcontrolplane.executor.ExecutorSettings.hash(value); }

    @Test void rejectsTamperedEvidenceAndBoundsBeforeParsingWithoutReturningPartialData() throws Exception {
        var binding=new io.refactorcontrolplane.governance.EvidenceStore.Binding(source.planScope(),"CAT-1","orphan",base,1,1,1,1);
        var fact=store.append("orphan",binding,"CODE_REVIEW","lead",JSON.createObjectNode().put("statement","historical claim").put("passed",true));
        String url="/api/v1/audit/export?expectedPlanScope="+source.planScope();
        try {
            var report=JSON.readTree(mvc.perform(get(url).header("Authorization","Bearer audit-secret")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
            assertEquals("UNREFERENCED",report.path("payload").path("evidence").get(0).path("referenceStatus").asText());
            assertEquals("UNVERIFIED",report.path("payload").path("evidence").get(0).path("executionBinding").asText());
            String original=jdbc.queryForObject("SELECT payload FROM evidence_record WHERE id=?",String.class,fact.id());
            jdbc.update("UPDATE evidence_record SET payload='secret-absolute-path-invalid-json' WHERE id=?",fact.id());
            var error=mvc.perform(get(url).header("Authorization","Bearer audit-secret")).andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();
            assertFalse(error.contains("secret-absolute-path"));
            jdbc.update("UPDATE evidence_record SET payload=? WHERE id=?","x".repeat(io.refactorcontrolplane.audit.AuditExportService.MAX_BYTES+1),fact.id());
            mvc.perform(get(url).header("Authorization","Bearer audit-secret")).andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.payload").doesNotExist());
            jdbc.update("UPDATE evidence_record SET payload=? WHERE id=?",original,fact.id());
        } finally {jdbc.update("DELETE FROM evidence_record WHERE id=?",fact.id());}
        jdbc.update("INSERT INTO audit_event(idempotency_key,task_id,event_type,payload,status,version,plan_scope) SELECT 'budget-'||X,'CAT-1','probe','invalid-json','PENDING',1,? FROM SYSTEM_RANGE(1,2001)",source.planScope());
        try {mvc.perform(get(url).header("Authorization","Bearer audit-secret")).andExpect(status().isPayloadTooLarge());}
        finally {jdbc.update("DELETE FROM audit_event WHERE idempotency_key LIKE 'budget-%'");}
    }

    @Test void exportsHistoricalAttemptsAndUnknownOperationsWithSensitiveRawFieldsExcluded() throws Exception {
        String marker="PRIVATE_SENTINEL"; var projection=JSON.valueToTree(runtime.auditSnapshot());
        for(String id:List.of("attempt-one","attempt-two")) {
            ((ObjectNode)projection).putObject("executions").putObject("CAT-1").put("taskId","CAT-1").put("executionId",id).put("branch","task/"+id).put("baseCommit",base).put("commitSha",base).put("path",marker);
            ((ObjectNode)projection).putObject("gateEvidence").putObject("CAT-1").put("CODE_REVIEW",id.equals("attempt-one")?"1".repeat(64):"2".repeat(64)).put("unrecognizedField",marker);
            var response=JSON.createObjectNode().put("journalVersion",2);response.set("projection",projection);response.putObject("response");
            jdbc.update("INSERT INTO audit_event(idempotency_key,task_id,event_type,payload,result,status,version,plan_scope) VALUES(?,'CAT-1','operator:accept_task',?,?,'COMPLETED',1,?)","history-"+id,JSON.createObjectNode().put("taskId","CAT-1").put("commitSha",base).put("actorSubject","business").put("taskToken",marker).put("statement",marker).toString(),response.toString(),source.planScope());
        }
        jdbc.update("INSERT INTO audit_event(idempotency_key,task_id,event_type,payload,status,version,plan_scope) VALUES('unknown','CAT-1','submit_changeset','{}','PENDING',1,?)",source.planScope());
        jdbc.update("INSERT INTO integration_record(id,kind,operation_key,actor,request_json,payload,plan_scope) VALUES('foreign','candidate','foreign','lead','{}','{}',?)","e".repeat(64));
        var wrongSemantic=store.append("wrong-semantic",new io.refactorcontrolplane.governance.EvidenceStore.Binding(source.planScope(),"CAT-1","attempt-one",base,99,99,99,99),"CODE_REVIEW","lead",JSON.createObjectNode().put("statement","Old version claim").put("passed",true));
        try {
            var before=runtime.auditSnapshot();
            String url="/api/v1/audit/export?expectedPlanScope="+source.planScope();
            var text=mvc.perform(get(url).header("Authorization","Bearer audit-secret")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            var payload=JSON.readTree(text).path("payload");
            assertEquals(2,payload.path("executions").size());assertFalse(text.contains(marker));
            assertEquals("UNMAPPED",payload.path("plan").path("requirementMapping").asText());
            assertEquals("UNKNOWN",payload.path("operations").get(2).path("actor").asText());
            assertEquals("PENDING",payload.path("operations").get(2).path("status").asText());
            assertEquals("attempt-one",payload.path("operations").get(0).path("execution").path("executionId").asText());
            assertEquals("attempt-two",payload.path("operations").get(1).path("execution").path("executionId").asText());
            assertEquals("1".repeat(64),payload.path("operations").get(0).path("gateEvidence").path("CODE_REVIEW").asText());
            assertEquals("TASK_EXECUTION_COMMIT_MATCHED",payload.path("evidence").get(0).path("executionBinding").asText());
            assertEquals("DECLARED_NOT_INDEPENDENTLY_REVERIFIED",payload.path("evidence").get(0).path("semanticBinding").asText());
            assertEquals(99,payload.path("evidence").get(0).path("binding").path("contextVersion").asInt());
            assertEquals(before,runtime.auditSnapshot());
            mvc.perform(get(url+"&candidateId=foreign").header("Authorization","Bearer audit-secret")).andExpect(status().isBadRequest());
        } finally {jdbc.update("DELETE FROM audit_event WHERE idempotency_key LIKE 'history-%' OR idempotency_key='unknown'");jdbc.update("DELETE FROM integration_record WHERE id='foreign'");jdbc.update("DELETE FROM evidence_record WHERE id=?",wrongSemantic.id());}
    }

    @Test void orphanModelReceiptsDoNotBypassTheNestedExportWhitelist() throws Exception {
        var binding=new io.refactorcontrolplane.governance.EvidenceStore.Binding(source.planScope(),"CAT-1","orphan-model",base,1,1,1,1);
        var content=JSON.createObjectNode().put("provenance","EXECUTOR_REPORTED");
        content.putArray("modelRuns").addObject().put("id","model-1").put("credential","PRIVATE_NESTED_SENTINEL").putArray("toolCalls")
                .addObject().put("id","tool-1").put("rawArguments","PRIVATE_NESTED_SENTINEL");
        var fact=store.append("orphan-model",binding,"EXECUTOR_MODEL_RUNS","http-worker",content);
        try {
            String text=mvc.perform(get("/api/v1/audit/export?expectedPlanScope="+source.planScope()).header("Authorization","Bearer audit-secret"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertFalse(text.contains("PRIVATE_NESTED_SENTINEL"));assertTrue(text.contains("EXECUTOR_REPORTED"));
        } finally {jdbc.update("DELETE FROM evidence_record WHERE id=?",fact.id());}
    }

    @Test void rejectsValidModelEvidenceLinkedThroughAnotherPlanScope() throws Exception {
        String foreignScope="d".repeat(64);var execution=new io.refactorcontrolplane.mcp.McpProjectRuntime.Execution("CAT-1","foreign-execution","task/foreign",".",base,null);
        var request=new io.refactorcontrolplane.executor.ExecutionProtocol.Request("a".repeat(64),foreignScope,1,1,"developer","http","b".repeat(64),execution,Set.of("src"),Set.of(".git"),List.of(),java.time.Instant.now());
        var model=new io.refactorcontrolplane.executor.ExecutorModelRun("model-1","internal","v1","1".repeat(64),"2".repeat(64),"3".repeat(64),42,List.of("read_source"),List.of());
        var result=new io.refactorcontrolplane.executor.ExecutionProtocol.Result(execution.executionId(),request.requestHash(),2,"SUCCEEDED",base,List.of(),List.of(model));
        var binding=new io.refactorcontrolplane.governance.EvidenceStore.Binding(foreignScope,"CAT-1",execution.executionId(),base,1,1,1,1);
        String ref=new io.refactorcontrolplane.executor.ExecutionModelEvidence(store,JSON).record(request,result,binding);
        var projection=(ObjectNode)JSON.valueToTree(runtime.auditSnapshot());
        projection.putObject("executorRuns").set(execution.executionId(),JSON.valueToTree(new io.refactorcontrolplane.executor.ExecutionProtocol.Run(request,"SUCCEEDED",result,"now",ref)));
        var response=JSON.createObjectNode().put("journalVersion",2);response.set("projection",projection);response.putObject("response");
        jdbc.update("INSERT INTO audit_event(idempotency_key,task_id,event_type,payload,result,status,version,plan_scope) VALUES('foreign-run','CAT-1','executor:complete','{}',?,'COMPLETED',1,?)",response.toString(),source.planScope());
        try {
            mvc.perform(get("/api/v1/audit/export?expectedPlanScope="+source.planScope()).header("Authorization","Bearer audit-secret")).andExpect(status().isConflict());
        } finally {jdbc.update("DELETE FROM audit_event WHERE idempotency_key='foreign-run'");jdbc.update("DELETE FROM evidence_record WHERE id=?",ref);}
    }

    @Test void refusesOversizedUtf8OutputEvenWhenRowAndClobCharacterBudgetsPass() throws Exception {
        var binding=new io.refactorcontrolplane.governance.EvidenceStore.Binding(source.planScope(),"CAT-1","orphan-large",base,1,1,1,1);
        try {
            for(int i=0;i<190;i++)store.append("utf8-budget-"+i,binding,"CODE_REVIEW","lead",JSON.createObjectNode().put("statement","审".repeat(15100)).put("passed",true));
            assertTrue(jdbc.queryForObject("SELECT SUM(CHAR_LENGTH(payload)) FROM evidence_record WHERE plan_scope=?",Long.class,source.planScope())<io.refactorcontrolplane.audit.AuditExportService.MAX_BYTES);
            mvc.perform(get("/api/v1/audit/export?expectedPlanScope="+source.planScope()).header("Authorization","Bearer audit-secret"))
                    .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.payload").doesNotExist());
        } finally {jdbc.update("DELETE FROM evidence_record WHERE operation_key LIKE 'utf8-budget-%'");}
    }

    @Test void retainedModelRunsFromAnOldAttemptAreHistoricalRatherThanCurrentTaskExecutions() throws Exception {
        var before=runtime.auditSnapshot();
        var execution=new io.refactorcontrolplane.mcp.McpProjectRuntime.Execution("CAT-1","retained-execution","task/retained",".",base,null);
        var request=new io.refactorcontrolplane.executor.ExecutionProtocol.Request("a".repeat(64),source.planScope(),1,1,"developer","http","b".repeat(64),execution,Set.of("src"),Set.of(".git"),List.of(),java.time.Instant.now());
        var model=new io.refactorcontrolplane.executor.ExecutorModelRun("model-1","internal","v1","1".repeat(64),"2".repeat(64),"3".repeat(64),42,List.of("read_source","unused_tool"),List.of());
        var result=new io.refactorcontrolplane.executor.ExecutionProtocol.Result(execution.executionId(),request.requestHash(),2,"SUCCEEDED",base,List.of(),List.of(model));
        var binding=new io.refactorcontrolplane.governance.EvidenceStore.Binding(source.planScope(),"CAT-1",execution.executionId(),base,1,1,1,1);
        String ref=new io.refactorcontrolplane.executor.ExecutionModelEvidence(store,JSON).record(request,result,binding);
        var run=new io.refactorcontrolplane.executor.ExecutionProtocol.Run(request,"SUCCEEDED",result,"now",ref);
        runtime.restore(new io.refactorcontrolplane.mcp.McpProjectRuntime.Projection(before.project(),before.leases(),before.executions(),before.assignmentOwners(),before.assignmentEpochs(),before.stoppedExecutions(),Map.of(execution.executionId(),run),before.gateEvidence()));
        try {
            byte[] bytes=mvc.perform(get("/api/v1/audit/export?expectedPlanScope="+source.planScope()).header("Authorization","Bearer audit-secret")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            var report=JSON.readTree(bytes);var payload=report.path("payload");
            assertEquals("HISTORICAL_RECORDED_REFERENCE",payload.path("evidence").get(0).path("referenceStatus").asText());
            assertEquals("VERIFIED_EXECUTOR_REPORTED",payload.path("executorRuns").get(0).path("modelBinding").asText());
            var receipt=payload.path("evidence").get(0).path("content").path("modelRuns").get(0);
            assertEquals(2,receipt.path("requestedTools").size());assertEquals(0,receipt.path("toolCalls").size());
            Files.write(Path.of("../.runtime/phase9c-model-audit.json"),bytes);
        } finally {runtime.restore(before);jdbc.update("DELETE FROM evidence_record WHERE id=?",ref);}
    }
}
