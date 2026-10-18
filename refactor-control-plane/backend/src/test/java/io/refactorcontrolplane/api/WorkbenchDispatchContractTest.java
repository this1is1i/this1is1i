package io.refactorcontrolplane.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import io.refactorcontrolplane.persistence.AuditEventRepository;

@SpringBootTest(properties={"control-plane.identity-file=classpath:review-identities.json", "spring.datasource.url=jdbc:h2:mem:dispatch"})
@AutoConfigureMockMvc
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class WorkbenchDispatchContractTest {
    @TempDir static Path directory;
    static Path repo;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AuditEventRepository audit;
    @Autowired DemoProjectService project;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) throws Exception {
        if (repo == null) {
            repo = Files.createDirectories(directory.resolve("source"));
            git("init", "-b", "main"); Files.writeString(repo.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
            git("add", "pom.xml"); git("-c", "user.name=Test", "-c", "user.email=test@localhost", "-c", "commit.gpgsign=false", "commit", "-m", "baseline");
        }
        registry.add("control-plane.demo.repository", () -> repo.toString());
    }
    @Test void directoryIsManagerOnlyAndNeverReturnsCredentialMaterial() throws Exception {
        mvc.perform(get("/api/v1/workbench").header("Authorization", "Bearer manager-test-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.assignees[*].subjectId", hasItem("developer")))
                .andExpect(jsonPath("$.tasks[0].claimable").value(true))
                .andExpect(content().string(not(containsString("tokenSha256"))));
        mvc.perform(get("/api/v1/workbench").header("Authorization", "Bearer developer-test-token"))
                .andExpect(jsonPath("$.assignees").doesNotExist());
    }
    @Test void assignmentAndClaimUseRealJournalAndOnlyTheAssigneeCanClaim() throws Exception {
        var input = mapper.createObjectNode().put("taskId", "WP-100").put("assignee", "developer").put("expectedVersion", 1).put("idempotencyKey", "dispatch-assign");
        mvc.perform(post("/api/v1/workbench/assign").header("Authorization", "Bearer manager-test-token").contentType("application/json").content(input.toString()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.assigned").value(true)).andExpect(content().string(not(containsString("taskToken"))));
        input.remove("assignee"); input.put("idempotencyKey", "dispatch-claim");
        mvc.perform(post("/api/v1/workbench/claim").header("Authorization", "Bearer lead-test-token").contentType("application/json").content(input.toString()))
                .andExpect(status().isForbidden());
        String main = git("rev-parse", "main");
        String result = mvc.perform(post("/api/v1/workbench/claim").header("Authorization", "Bearer developer-test-token").contentType("application/json").content(input.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.task.status").value("CLAIMED"))
                .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsString();
        var execution = mapper.readTree(result).path("task").path("execution");
        assertTrue(Files.isDirectory(Path.of(execution.path("path").asText())));
        assertEquals(main, git("rev-parse", "main"));
        String replay = mvc.perform(post("/api/v1/workbench/claim").header("Authorization", "Bearer developer-test-token").contentType("application/json").content(input.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(mapper.readTree(result), mapper.readTree(replay));
        assertEquals("COMPLETED", audit.findByIdempotencyKey("dispatch-claim").orElseThrow().status());
        assertFalse(result.contains("taskToken"));
        var recovered = new io.refactorcontrolplane.mcp.McpProjectRuntime(project, audit, mapper);
        var journal = new io.refactorcontrolplane.mcp.ProjectMcpController(project, recovered, audit, mapper);
        var principal = new io.refactorcontrolplane.governance.LocalIdentityRegistry.Principal("developer", "legacy-blog",
                Set.of(io.refactorcontrolplane.governance.ProjectRole.DEVELOPER));
        assertEquals(mapper.readTree(result), mapper.valueToTree(journal.claimAssignedTask(principal, input)));
        assertFalse(audit.findAll().toString().contains("developer-test-token"));
        input.put("assignee", "lead").put("idempotencyKey", "late-reassign");
        mvc.perform(post("/api/v1/workbench/assign").header("Authorization", "Bearer manager-test-token").contentType("application/json").content(input.toString()))
                .andExpect(status().isConflict());
    }

    @Test void malformedVersionsAndUnknownMembersAreRejectedBeforeJournalWrites() throws Exception {
        var input = mapper.createObjectNode().put("taskId", "WP-100").put("assignee", "unknown-person").put("expectedVersion", 1).put("idempotencyKey", "invalid-member");
        mvc.perform(post("/api/v1/workbench/assign").contentType("application/json").content(input.toString())).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/workbench/assign").header("Authorization", "Bearer manager-test-token").contentType("application/json").content(input.toString())).andExpect(status().isForbidden());
        assertTrue(audit.findByIdempotencyKey("invalid-member").isEmpty());
        input.put("assignee", "developer").put("idempotencyKey", "invalid-version").put("expectedVersion", 1.5);
        mvc.perform(post("/api/v1/workbench/assign").header("Authorization", "Bearer manager-test-token").contentType("application/json").content(input.toString())).andExpect(status().isBadRequest());
        assertTrue(audit.findByIdempotencyKey("invalid-version").isEmpty());
    }
    @Test void untrustedFieldsAndUnmetDependenciesCannotCreateAnExecution() throws Exception {
        var input = mapper.createObjectNode().put("taskId", "WP-110").put("assignee", "developer").put("expectedVersion", 1).put("idempotencyKey", "dep-assign");
        mvc.perform(post("/api/v1/workbench/assign").header("Authorization", "Bearer qa-test-token").contentType("application/json").content(input.toString())).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/workbench/assign").header("Authorization", "Bearer manager-test-token").contentType("application/json").content(input.toString())).andExpect(status().isOk());
        input.remove("assignee"); input.put("idempotencyKey", "bad-field").put("actorSubject", "developer");
        mvc.perform(post("/api/v1/workbench/claim").header("Authorization", "Bearer developer-test-token").contentType("application/json").content(input.toString())).andExpect(status().isBadRequest());
        assertTrue(audit.findByIdempotencyKey("bad-field").isEmpty());
        input.remove("actorSubject"); input.put("idempotencyKey", "dep-claim");
        mvc.perform(post("/api/v1/workbench/claim").header("Authorization", "Bearer developer-test-token").contentType("application/json").content(input.toString())).andExpect(status().isConflict());
        mvc.perform(get("/api/v1/workbench").header("Authorization", "Bearer developer-test-token"))
                .andExpect(jsonPath("$.tasks[1].claimable").value(false)).andExpect(jsonPath("$.tasks[1].execution").doesNotExist());
    }
    static String git(String... arguments) throws Exception {
        var command = new ArrayList<>(List.of("git", "-C", repo.toString())); command.addAll(List.of(arguments));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output); return output.trim();
    }
}
