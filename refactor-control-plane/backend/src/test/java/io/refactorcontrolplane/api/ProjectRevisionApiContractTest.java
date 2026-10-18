package io.refactorcontrolplane.api;

import static io.refactorcontrolplane.api.ConfiguredProjectApiContractTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.refactorcontrolplane.project.ConfiguredProjectService;
import io.refactorcontrolplane.mcp.McpProjectRuntime;
import io.refactorcontrolplane.persistence.AuditEventRepository;
import io.refactorcontrolplane.governance.LocalIdentityRegistry.Principal;
import io.refactorcontrolplane.governance.ProjectRole;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties={"control-plane.project.mode=configured", "control-plane.identity-file=classpath:review-identities.json",
        "spring.datasource.url=jdbc:h2:mem:revision-api"})
@AutoConfigureMockMvc
class ProjectRevisionApiContractTest {
    @TempDir static Path directory;
    static Path repo, file; static String base;
    @Autowired MockMvc mvc; @Autowired ObjectMapper mapper; @Autowired JdbcTemplate jdbc;
    @Autowired ConfiguredProjectService source;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) throws Exception {
        repo = Files.createDirectories(directory.resolve("source")); git(repo,"init","-b","main");
        Files.createDirectories(repo.resolve("src")); Files.writeString(repo.resolve("src/pom.xml"),"<project/>",StandardCharsets.UTF_8);
        git(repo,"add","."); commit(repo); base=git(repo,"rev-parse","HEAD");
        file=directory.resolve("project.json"); Files.writeString(file,configuration(repo,base,"src").toString(),StandardCharsets.UTF_8);
        registry.add("control-plane.project.definition-file",file::toString);
    }
    @Test void revisionRequiresNewIndependentApprovalsAndRestartsWithoutOldProjection() throws Exception {
        var state=mapper.readTree(mvc.perform(get("/api/v1/project/plan").header("Authorization","Bearer manager-test-token"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String old=state.path("fingerprint").asText();
        decision("/api/v1/project/plan/decisions",old,"architect","ARCHITECTURE",200);
        decision("/api/v1/project/plan/decisions",old,"manager","EXECUTION",200);
        mvc.perform(post("/api/v1/workbench/assign").header("Authorization","Bearer manager-test-token").contentType("application/json")
                .content("{\"taskId\":\"CAT-1\",\"assignee\":\"developer\",\"expectedVersion\":1,\"idempotencyKey\":\"old-assign\"}"))
                .andExpect(status().isOk());
        var next=configuration(repo,base,"src"); ((ObjectNode)next.path("plan")).put("apiContract","catalog-v2");
        var request=mapper.createObjectNode().put("expectedFingerprint",old).set("definition",next);
        mvc.perform(post("/api/v1/project/plan/revisions").header("Authorization","Bearer developer-test-token")
                .contentType("application/json").content(request.toString())).andExpect(status().isForbidden());
        String body=mvc.perform(post("/api/v1/project/plan/revisions").header("Authorization","Bearer architect-test-token")
                .contentType("application/json").content(request.toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String revision=mapper.readTree(body).path("fingerprint").asText(); assertNotEquals(old,revision);
        activate(old,revision,409);
        String endpoint="/api/v1/project/plan/revisions/"+revision+"/decisions";
        decision(endpoint,revision,"manager","EXECUTION",409);
        decision(endpoint,revision,"architect","ARCHITECTURE",200);
        decision(endpoint,revision,"manager","EXECUTION",200);
        activate("stale",revision,409); activate(old,revision,200); activate(old,revision,200);
        assertThrows(IllegalStateException.class,source::requireExecutable);
        var restarted=new ConfiguredProjectService(file.toString(),jdbc,mapper);
        assertTrue(restarted.approved()); assertEquals("catalog-v2",restarted.overview().plan().apiContract());
        var audit=new AuditEventRepository(jdbc); var runtime=new McpProjectRuntime(restarted,audit,mapper);
        assertEquals("task:CAT-1", ((java.util.List<java.util.Map<String,Object>>)runtime.workbenchSnapshot().get("tasks")).getFirst().get("owner"));
        assertTrue(audit.findAll().isEmpty());
        assertThrows(IllegalStateException.class,()->audit.findByIdempotencyKey("old-assign"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_event",Integer.class));
        assertEquals(base,git(repo,"rev-parse","main"));
    }
    void decision(String path,String fp,String actor,String decision,int status) throws Exception {
        mvc.perform(post(path).header("Authorization","Bearer "+actor+"-test-token").contentType("application/json")
                .content(mapper.createObjectNode().put("expectedFingerprint",fp).put("decision",decision).toString()))
                .andExpect(status().is(status));
    }
    void activate(String old,String next,int status) throws Exception {
        mvc.perform(post("/api/v1/project/plan/revisions/"+next+"/activate").header("Authorization","Bearer manager-test-token")
                .contentType("application/json").content(mapper.createObjectNode().put("expectedFingerprint",old).toString()))
                .andExpect(status().is(status));
    }
}
