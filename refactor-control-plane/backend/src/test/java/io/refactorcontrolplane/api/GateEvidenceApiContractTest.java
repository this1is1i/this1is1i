package io.refactorcontrolplane.api;

import static io.refactorcontrolplane.api.ConfiguredProjectApiContractTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.refactorcontrolplane.governance.*;
import io.refactorcontrolplane.integration.*;
import io.refactorcontrolplane.mcp.*;
import io.refactorcontrolplane.project.ConfiguredProjectService;
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

@SpringBootTest(properties={"control-plane.project.mode=configured", "control-plane.identity-file=classpath:review-identities.json",
        "spring.datasource.url=jdbc:h2:mem:gate-evidence", "control-plane.executors.auto-poll=false"})
@AutoConfigureMockMvc @DirtiesContext
class GateEvidenceApiContractTest {
    @TempDir static Path directory;
    static Path repo,barrier; static String base;
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    @Autowired MockMvc mvc;
    @Autowired ConfiguredProjectService source;
    @Autowired McpProjectRuntime runtime;
    @Autowired ProjectMcpController journal;
    @Autowired IntegrationService integration;
    @Autowired GateEvidencePolicy evidencePolicy;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired io.refactorcontrolplane.audit.AuditExportService auditExport;
    String sha;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) throws Exception {
        repo = Files.createDirectories(directory.resolve("source")); git(repo, "init", "-b", "main");
        Files.createDirectory(repo.resolve("src")); Files.writeString(repo.resolve("src/pom.xml"), "<project/>", StandardCharsets.UTF_8);
        git(repo,"add","."); commit(repo); base=git(repo,"rev-parse","HEAD");
        var plan=configuration(repo,base,"src");
        barrier=Files.createDirectory(directory.resolve("barrier"));
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        var argv=List.of(java,"-cp",Path.of("target/test-classes").toAbsolutePath().normalize().toString(),AuditBlockingCheck.class.getName(),barrier.toString());
        var check=((ArrayNode)plan.path("validationProfiles").get(0).path("checks")).addObject().put("id","audit-barrier").put("workingDirectory","src").put("timeoutSeconds",30);
        check.set("argv",JSON.valueToTree(argv));
        ((ArrayNode)plan.path("plan").path("workPackages").get(0).path("acceptanceCommands")).add(String.join(" ",argv));
        plan.putArray("requirements").addObject().put("id","REQ-1").put("statement","Preserve catalog behavior").putArray("taskIds").add("CAT-1");
        var adr=plan.putArray("architectureDecisions").addObject().put("id","ADR-1").put("statement","Use the approved catalog boundary");
        adr.putArray("taskIds").add("CAT-1"); adr.putArray("evidence").addObject().put("path","src/pom.xml").put("blobId",git(repo,"rev-parse",base+":src/pom.xml"));
        Path file=directory.resolve("project.json"); Files.writeString(file,plan.toString(),StandardCharsets.UTF_8);
        properties.add("control-plane.project.definition-file",file::toString);
    }
    @Test void platformValidationAndIndependentStatementsAreRequiredAndRecheckedAtEveryReleaseBoundary() throws Exception {
        source.decide(actor("architect",ProjectRole.ARCHITECT),source.planScope(),"ARCHITECTURE");
        source.decide(actor("manager",ProjectRole.PROJECT_MANAGER),source.planScope(),"EXECUTION");
        journal.operatorCommand(actor("manager",ProjectRole.PROJECT_MANAGER),"assign_task",envelope().put("assignee","developer"));
        journal.claimAssignedTask(actor("developer",ProjectRole.DEVELOPER),envelope());
        var execution=(McpProjectRuntime.Execution)runtime.task("CAT-1").get("execution"); Path tree=Path.of(execution.path());
        Files.writeString(tree.resolve("src/result.txt"),"validated output",StandardCharsets.UTF_8); git(tree,"add","."); commit(tree); sha=git(tree,"rev-parse","HEAD");
        mcp("reserve_paths",envelope().put("ownerId","developer").put("resourcePath","src"));
        mcp("submit_changeset",envelope().put("commitSha",sha));
        command("lead","start_review",JSON.createObjectNode(),200);
        command("lead","record_gate",gate("CODE_REVIEW").put("evidenceRef","arbitrary-url"),400);
        command("developer","record_gate",gate("CODE_REVIEW").put("statement","self approval"),403);
        command("lead","record_gate",gate("CODE_REVIEW").put("statement","Review of a previous execution").put("taskExecutionId","old-execution"),409);
        var review=command("lead","record_gate",gate("CODE_REVIEW").put("statement","Reviewed actual diff; no unresolved findings."),200);
        String reviewId=review.path("evidenceRef").asText(); assertTrue(reviewId.matches("[a-f0-9]{64}"));
        mvc.perform(get("/api/v1/evidence/"+reviewId)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/evidence/"+reviewId).header("Authorization","Bearer lead-test-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.binding.commitSha").value(sha)).andExpect(jsonPath("$.author").value("lead"));
        command("qa","start_testing",JSON.createObjectNode(),200);
        // A historic TESTING task may need to replace a missing review reference without repeating execution.
        command("lead","record_gate",gate("CODE_REVIEW").put("statement","Reviewed actual diff; no unresolved findings."),200);
        reviewId=JSON.valueToTree(runtime.workbenchSnapshot()).path("tasks").get(0).path("gateEvidence").path("CODE_REVIEW").asText();
        command("qa","record_gate",gate("UNIT_TEST").put("statement","external worker says passed"),400);
        command("qa","record_gate",gate("UNIT_TEST").put("evidenceRef",reviewId),409);
        var candidate=integration.create(actor("lead",ProjectRole.ENGINEERING_LEAD),new IntegrationService.Create("candidate",1,List.of("CAT-1"),"catalog-check")).candidate();
        String candidateId=candidate.plan().id(), fingerprint=candidate.plan().fingerprint();
        var validation=integration.validate(actor("qa",ProjectRole.QA_LEAD),candidateId,new IntegrationService.Validate("validate",fingerprint));
        assertEquals("PASSED",validation.state());
        for(String gate:List.of("UNIT_TEST","INTEGRATION_TEST")) command("qa","record_gate",gate(gate).put("candidateId",candidateId).put("validationId",validation.id()),200);
        command("business","record_gate",gate("BUSINESS_ACCEPTANCE").put("statement","Confirmed the approved business behavior."),200);
        String original=jdbc.queryForObject("SELECT payload FROM evidence_record WHERE id=?",String.class,reviewId);
        jdbc.update("UPDATE evidence_record SET payload=? WHERE id=?","{}",reviewId);
        command("business","accept_task",JSON.createObjectNode(),409);
        jdbc.update("UPDATE evidence_record SET payload=? WHERE id=?",original,reviewId);
        command("business","accept_task",JSON.createObjectNode(),200);
        var recovered=new McpProjectRuntime(source,new io.refactorcontrolplane.persistence.AuditEventRepository(jdbc),mapper,evidencePolicy);
        recovered.requireIntegrationAccepted(List.of("CAT-1"));
        assertEquals(runtime.snapshot().gateEvidence(),recovered.snapshot().gateEvidence());
        var pending=envelope().put("idempotencyKey","pending-merge").put("commitSha",sha).put("actorSubject","lead").put("_journalVersion",2);
        pending.putArray("actorRoles").add("ENGINEERING_LEAD");
        var recoveryAudit=new io.refactorcontrolplane.persistence.AuditEventRepository(jdbc); recoveryAudit.bindScope(source.planScope());
        recoveryAudit.record("pending-merge","CAT-1","operator:merge_proposal",pending.toString(),runtime.contextVersion());
        new McpProjectRuntime(source,recoveryAudit,mapper,evidencePolicy);
        assertEquals("FAILED",recoveryAudit.findByIdempotencyKey("pending-merge").orElseThrow().status(),"Recovery must not bypass the integration merge guard");
        // A new verification invalidates old test evidence even while the task is already ACCEPTED.
        var fresh=integration.validate(actor("qa",ProjectRole.QA_LEAD),candidateId,new IntegrationService.Validate("validate-again",fingerprint));
        assertEquals("NOT_ELIGIBLE_AT_OBSERVATION",export(candidateId).path("payload").path("selectedCandidate").path("eligibility").asText());
        assertThrows(IllegalStateException.class,()->recovered.requireIntegrationAccepted(List.of("CAT-1")));
        assertThrows(IllegalStateException.class,()->integration.propose(actor("lead",ProjectRole.ENGINEERING_LEAD),candidateId,new IntegrationService.Propose("stale-proposal",fingerprint,fresh.id())));
        for(String gate:List.of("UNIT_TEST","INTEGRATION_TEST")) command("qa","record_gate",gate(gate).put("candidateId",candidateId).put("validationId",fresh.id()),200);
        var proposal=integration.propose(actor("lead",ProjectRole.ENGINEERING_LEAD),candidateId,new IntegrationService.Propose("proposal",fingerprint,fresh.id()));
        assertFalse(proposal.automaticallyMergeMain());
        var before=runtime.auditSnapshot();
        var storedBefore=jdbc.query("SELECT payload FROM integration_record ORDER BY id",(rs,n)->rs.getString(1));
        var report=export(candidateId); var payload=report.path("payload");
        assertEquals("ELIGIBLE_AT_OBSERVATION",payload.path("selectedCandidate").path("eligibility").asText());
        assertEquals("REQ-1",payload.path("plan").path("requirements").get(0).path("id").asText());
        assertEquals("ADR-1",payload.path("plan").path("architectureDecisions").get(0).path("id").asText());
        assertEquals(sha,payload.path("executions").get(0).path("commitSha").asText());
        assertTrue(payload.path("operations").findValuesAsText("actor").contains("business"));
        assertTrue(payload.path("evidence").findValuesAsText("kind").containsAll(List.of("CODE_REVIEW","UNIT_TEST","INTEGRATION_TEST","BUSINESS_ACCEPTANCE")));
        assertTrue(payload.path("integrations").findValuesAsText("id").contains(proposal.id()));
        assertEquals(before,runtime.auditSnapshot());
        assertEquals(storedBefore,jdbc.query("SELECT payload FROM integration_record ORDER BY id",(rs,n)->rs.getString(1)));
        assertFalse(report.toString().contains(repo.toString().replace("\\","\\\\")));
        assertEquals(io.refactorcontrolplane.executor.ExecutorSettings.hash(JSON.writeValueAsString(payload)),report.path("integrity").path("payloadSha256").asText());
        Files.write(Path.of("../.runtime/phase9c-audit.json"),JSON.writeValueAsBytes(report));
        jdbc.update("UPDATE integration_record SET plan_scope=? WHERE id=?","f".repeat(64),fresh.id());
        assertEquals("NOT_ELIGIBLE_AT_OBSERVATION",export(candidateId).path("payload").path("selectedCandidate").path("eligibility").asText());
        jdbc.update("UPDATE integration_record SET plan_scope=? WHERE id=?",source.planScope(),fresh.id());
        String testId=JSON.valueToTree(runtime.workbenchSnapshot()).path("tasks").get(0).path("gateEvidence").path("UNIT_TEST").asText();
        jdbc.update("DELETE FROM evidence_record WHERE id=?",testId);
        assertThrows(IllegalArgumentException.class,()->integration.propose(actor("lead",ProjectRole.ENGINEERING_LEAD),candidateId,new IntegrationService.Propose("missing-proposal",fingerprint,fresh.id())));
        Files.writeString(barrier.resolve("block"),"block",StandardCharsets.UTF_8);
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var running=pool.submit(()->integration.validate(actor("qa",ProjectRole.QA_LEAD),candidateId,new IntegrationService.Validate("blocking-validation",fingerprint)));
            try {
                long deadline=System.nanoTime()+java.time.Duration.ofSeconds(10).toNanos();
                while(!Files.exists(barrier.resolve("entered")) && System.nanoTime()<deadline)Thread.sleep(25);
                assertTrue(Files.exists(barrier.resolve("entered")),"Real validation process must enter its barrier");
                var observed=org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(5),()->export(candidateId));
                assertEquals("NOT_ELIGIBLE_AT_OBSERVATION",observed.path("payload").path("selectedCandidate").path("eligibility").asText());
                assertTrue(observed.path("payload").path("integrations").findValuesAsText("state").contains("RUNNING"));
            } finally {Files.writeString(barrier.resolve("release"),"release",StandardCharsets.UTF_8);}
            assertEquals("PASSED",running.get(10,java.util.concurrent.TimeUnit.SECONDS).state());
        }
        assertEquals(base,git(repo,"rev-parse","main"));
    }
    JsonNode export(String candidateId) throws Exception {
        return JSON.readTree(auditExport.export(actor("auditor",ProjectRole.AUDITOR),source.planScope(),candidateId));
    }
    ObjectNode gate(String gate) { return JSON.createObjectNode().put("gate",gate).put("passed",true); }
    ObjectNode envelope() { return JSON.createObjectNode().put("taskId","CAT-1").put("expectedVersion",runtime.contextVersion()).put("idempotencyKey",UUID.randomUUID().toString()); }
    JsonNode command(String actor,String action,ObjectNode fields,int status) throws Exception {
        var body=envelope().put("action",action).put("commitSha",sha); body.setAll(fields);
        if (!body.has("taskExecutionId")) body.put("taskExecutionId",((McpProjectRuntime.Execution)runtime.task("CAT-1").get("execution")).executionId());
        return JSON.readTree(mvc.perform(post("/api/v1/governance/commands").header("Authorization","Bearer "+actor+"-test-token")
                .contentType("application/json").content(body.toString())).andExpect(status().is(status)).andReturn().getResponse().getContentAsString());
    }
    void mcp(String name,ObjectNode args) {
        args.put("taskToken",runtime.assignmentToken("CAT-1")); var params=JSON.createObjectNode().put("name",name); params.set("arguments",args);
        var response=JSON.valueToTree(journal.handle(new McpRequest("2.0",JSON.getNodeFactory().numberNode(1),"tools/call",params)).getBody());
        assertFalse(response.has("error"),response::toString);
    }
    static LocalIdentityRegistry.Principal actor(String id,ProjectRole role) { return new LocalIdentityRegistry.Principal(id,"legacy-blog",Set.of(role)); }
}
