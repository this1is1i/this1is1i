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
    static Path repo; static String base;
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    @Autowired MockMvc mvc;
    @Autowired ConfiguredProjectService source;
    @Autowired McpProjectRuntime runtime;
    @Autowired ProjectMcpController journal;
    @Autowired IntegrationService integration;
    @Autowired GateEvidencePolicy evidencePolicy;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    String sha;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) throws Exception {
        repo = Files.createDirectories(directory.resolve("source")); git(repo, "init", "-b", "main");
        Files.createDirectory(repo.resolve("src")); Files.writeString(repo.resolve("src/pom.xml"), "<project/>", StandardCharsets.UTF_8);
        git(repo,"add","."); commit(repo); base=git(repo,"rev-parse","HEAD");
        Path file=directory.resolve("project.json"); Files.writeString(file,configuration(repo,base,"src").toString(),StandardCharsets.UTF_8);
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
        assertThrows(IllegalStateException.class,()->recovered.requireIntegrationAccepted(List.of("CAT-1")));
        assertThrows(IllegalStateException.class,()->integration.propose(actor("lead",ProjectRole.ENGINEERING_LEAD),candidateId,new IntegrationService.Propose("stale-proposal",fingerprint,fresh.id())));
        for(String gate:List.of("UNIT_TEST","INTEGRATION_TEST")) command("qa","record_gate",gate(gate).put("candidateId",candidateId).put("validationId",fresh.id()),200);
        var proposal=integration.propose(actor("lead",ProjectRole.ENGINEERING_LEAD),candidateId,new IntegrationService.Propose("proposal",fingerprint,fresh.id()));
        assertFalse(proposal.automaticallyMergeMain());
        String testId=JSON.valueToTree(runtime.workbenchSnapshot()).path("tasks").get(0).path("gateEvidence").path("UNIT_TEST").asText();
        jdbc.update("DELETE FROM evidence_record WHERE id=?",testId);
        assertThrows(IllegalArgumentException.class,()->integration.propose(actor("lead",ProjectRole.ENGINEERING_LEAD),candidateId,new IntegrationService.Propose("missing-proposal",fingerprint,fresh.id())));
        assertEquals(base,git(repo,"rev-parse","main"));
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
