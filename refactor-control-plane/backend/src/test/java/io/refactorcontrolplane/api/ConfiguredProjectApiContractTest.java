package io.refactorcontrolplane.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties={"control-plane.project.mode=configured", "control-plane.identity-file=classpath:review-identities.json",
        "spring.datasource.url=jdbc:h2:mem:configured-api"})
@AutoConfigureMockMvc
public class ConfiguredProjectApiContractTest {
    @TempDir static Path directory;
    static Path repo, definition;
    static String base;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test void configuredControlPlaneDoesNotHostBusinessEndpointsOrTables() throws Exception {
        mvc.perform(get("/api/articles")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/article-migration/preview").contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME IN ('MODERN_ARTICLES','LEGACY_POST_IMPORT','ARTICLE_MIGRATION_RUN')",Integer.class));
    }

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) throws Exception {
        repo = Files.createDirectories(directory.resolve("source"));
        git(repo, "init", "-b", "main");
        Files.createDirectories(repo.resolve("services/catalog"));
        Files.writeString(repo.resolve("services/catalog/pom.xml"), "<project/>", StandardCharsets.UTF_8);
        git(repo, "add", "."); commit(repo); base = git(repo, "rev-parse", "HEAD");
        definition = directory.resolve("project.json");
        Files.writeString(definition, configuration(repo, base, "services/catalog").toString(), StandardCharsets.UTF_8);
        registry.add("control-plane.project.definition-file", () -> definition.toString());
        registry.add("control-plane.demo.repository", () -> repo.toString());
    }

    @Test void configuredPlanRequiresBothDecisionsBeforeRealAssignmentAndClaim() throws Exception {
        var pending = mvc.perform(get("/api/v1/workbench").header("Authorization", "Bearer manager-test-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tasks[0].id").value("CAT-1"))
                .andExpect(jsonPath("$.tasks[0].claimable").value(false)).andReturn();
        assertFalse(pending.getResponse().getContentAsString().contains("WP-100"));
        var command = mapper.createObjectNode().put("taskId", "CAT-1").put("assignee", "developer")
                .put("expectedVersion", 1).put("idempotencyKey", "configured-assign");
        mvc.perform(post("/api/v1/workbench/assign").header("Authorization", "Bearer manager-test-token")
                .contentType("application/json").content(command.toString())).andExpect(status().isConflict());
        mvc.perform(get("/api/v1/project/plan")).andExpect(status().isForbidden());
        String state = mvc.perform(get("/api/v1/project/plan").header("Authorization", "Bearer manager-test-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString();
        String fingerprint = mapper.readTree(state).path("fingerprint").asText();
        var approval = mapper.createObjectNode().put("expectedFingerprint", fingerprint).put("decision", "ARCHITECTURE");
        mvc.perform(post("/api/v1/project/plan/decisions").header("Authorization", "Bearer developer-test-token")
                .contentType("application/json").content(approval.toString())).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/project/plan/decisions").header("Authorization", "Bearer architect-test-token")
                .contentType("application/json").content(approval.toString())).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));
        approval.put("decision", "EXECUTION");
        mvc.perform(post("/api/v1/project/plan/decisions").header("Authorization", "Bearer manager-test-token")
                .contentType("application/json").content(approval.toString())).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        mvc.perform(post("/api/v1/workbench/assign").header("Authorization", "Bearer manager-test-token")
                .contentType("application/json").content(command.toString())).andExpect(status().isOk());
        command.remove("assignee"); command.put("idempotencyKey", "configured-claim");
        String result = mvc.perform(post("/api/v1/workbench/claim").header("Authorization", "Bearer developer-test-token")
                .contentType("application/json").content(command.toString())).andExpect(status().isOk())
                .andExpect(jsonPath("$.task.workingDirectory").value("services/catalog"))
                .andReturn().getResponse().getContentAsString();
        assertTrue(Files.isRegularFile(Path.of(mapper.readTree(result).path("task").path("execution").path("path").asText())
                .resolve("services/catalog/pom.xml")));
        assertEquals(base, git(repo, "rev-parse", "main"));
    }

    public static ObjectNode configuration(Path repo, String base, String layout) {
        var mapper = new ObjectMapper();
        return mapper.valueToTree(Map.ofEntries(
                Map.entry("projectId", "legacy-blog"), Map.entry("repository", repo.toString()),
                Map.entry("targetRef", "refs/heads/main"), Map.entry("forbiddenPaths", List.of(".git", "private-data")),
                Map.entry("plan", Map.ofEntries(Map.entry("baseCommit", base), Map.entry("targetArchitectureMermaid", "flowchart LR\n UI --> API"),
                        Map.entry("workPackages", List.of(Map.of("id", "CAT-1", "title", "Catalog implementation", "role", "DEVELOPER",
                                "dependencies", List.of(), "allowedPaths", List.of(layout), "estimatedHours", 2,
                                "workingDirectory", layout, "acceptanceCommands", List.of("git diff --check")))),
                        Map.entry("criticalPath", List.of("CAT-1")), Map.entry("parallelExecutionGroups", Map.of()),
                        Map.entry("apiContract", "catalog-v1"), Map.entry("databaseCompatibility", "No storage change"),
                        Map.entry("functionalParity", Map.of("catalog", "preserve")), Map.entry("requiredEvidence", List.of("review")))),
                Map.entry("resources", Map.of("catalog", Map.of("kind", "PATH", "locator", layout, "version", "1"))),
                Map.entry("taskResources", Map.of("CAT-1", List.of("catalog"))),
                Map.entry("directoryCreators", Map.of()),
                Map.entry("taskProfiles", Map.of("CAT-1", "catalog-check")),
                Map.entry("validationProfiles", List.of(Map.of("id", "catalog-check", "version", "1", "checks", List.of(
                        Map.of("id", "diff", "argv", List.of("git", "diff", "--check"), "workingDirectory", layout, "timeoutSeconds", 10)))))));
    }
    public static void commit(Path repo) throws Exception {
        git(repo, "-c", "user.name=Test", "-c", "user.email=test@localhost", "-c", "commit.gpgsign=false", "commit", "-m", "fixture");
    }
    public static String git(Path repo, String... args) throws Exception {
        var command = new ArrayList<>(List.of("git", "-C", repo.toString())); command.addAll(List.of(args));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String result = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), result); return result.trim();
    }
}
