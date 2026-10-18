package io.refactorcontrolplane.project;

import static org.junit.jupiter.api.Assertions.*;
import static io.refactorcontrolplane.api.ConfiguredProjectApiContractTest.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.refactorcontrolplane.governance.LocalIdentityRegistry.Principal;
import io.refactorcontrolplane.governance.ProjectRole;
import io.refactorcontrolplane.mcp.*;
import io.refactorcontrolplane.persistence.AuditEventRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.*;

class SchedulingContractTest {
    @TempDir Path directory;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final Principal manager = actor("manager", ProjectRole.PROJECT_MANAGER);
    EmbeddedDatabase db;
    JdbcTemplate jdbc;
    Path repo, file;
    String base;
    ObjectNode input;
    ConfiguredProjectService source;
    McpProjectRuntime runtime;
    ProjectMcpController journal;

    @BeforeEach void setup() throws Exception {
        db = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2)
                .addScript("schema.sql").addScript("db/migration/V3__integration_candidates.sql").build();
        jdbc = new JdbcTemplate(db);
        repo = Files.createDirectories(directory.resolve("source"));
        git(repo, "init", "-b", "main");
        Files.createDirectories(repo.resolve("src"));
        Files.writeString(repo.resolve("src/pom.xml"), "<project/>", StandardCharsets.UTF_8);
        git(repo, "add", "."); commit(repo); base = git(repo, "rev-parse", "HEAD");
        input = configuration(repo, base, "src"); file = directory.resolve("project.json");
        var first = (ObjectNode) input.path("plan").path("workPackages").get(0);
        for (String id : List.of("CAT-2", "CAT-3")) {
            ((ArrayNode) input.path("plan").path("workPackages")).add(first.deepCopy().put("id", id));
            ((ObjectNode) input.path("taskProfiles")).put(id, "catalog-check");
            ((ObjectNode) input.path("taskResources")).putArray(id).add("catalog");
        }
        ((ObjectNode) input.path("resources")).putObject("test-db")
                .put("kind", "ENVIRONMENT").put("locator", "isolated-test-db").put("version", "1");
        for (String id : List.of("CAT-1", "CAT-2", "CAT-3"))
            ((ArrayNode) input.path("taskResources").path(id)).add("test-db");
        var scheduling = input.putObject("scheduling");
        scheduling.putObject("actorCapacities").put("developer", 2).put("other", 2);
        scheduling.putObject("environmentCapacities").put("test-db", 2);
    }
    @AfterEach void close() { db.shutdown(); }

    @ParameterizedTest @ValueSource(strings={"actor", "environment"})
    void admissionIsAtomicAndOccupiedSlotsSurviveRestartUntilVerifiedSubmission(String bottleneck) throws Exception {
        start();
        assign("CAT-1", "developer"); assign("CAT-2", "developer");
        assign("CAT-3", bottleneck.equals("actor") ? "developer" : "other");
        var one = claim("CAT-1", "developer"); claim("CAT-2", "developer");
        String trees = git(repo, "worktree", "list", "--porcelain");
        assertThrows(IllegalStateException.class, () -> claim("CAT-3", bottleneck.equals("actor") ? "developer" : "other"));
        assertEquals(trees, git(repo, "worktree", "list", "--porcelain"));
        JsonNode blocked = mapper.valueToTree(runtime.task("CAT-3")).path("capacityBlockers");
        assertTrue(blocked.toString().contains(bottleneck.equals("actor") ? "ACTOR" : "ENVIRONMENT"));
        assertTrue(runtime.readyTasks().isEmpty());
        restart();
        assertEquals(blocked, mapper.valueToTree(runtime.task("CAT-3")).path("capacityBlockers"));
        Path tree = Path.of(one.path("task").path("execution").path("path").asText());
        Files.writeString(tree.resolve("src/output.txt"), "verified output", StandardCharsets.UTF_8);
        git(tree, "add", "."); commit(tree);
        call("CAT-1", "reserve_paths", mapper.createObjectNode().put("ownerId", "developer").put("resourcePath", "src"));
        call("CAT-1", "submit_changeset", mapper.createObjectNode().put("commitSha", git(tree, "rev-parse", "HEAD")));
        assertEquals(1, runtime.readyTasks().size());
        claim("CAT-3", bottleneck.equals("actor") ? "developer" : "other");
        assertEquals(base, git(repo, "rev-parse", "main"));
    }

    @ParameterizedTest @ValueSource(strings={"BLOCKED", "CLAIMED", "RUNNING"})
    void unknownExecutionKeepsCapacityUntilIndependentStopConfirmationAndFencesOldCredentials(String initialStatus) throws Exception {
        ((ObjectNode) input.path("scheduling").path("environmentCapacities")).put("test-db", 1);
        start(); assign("CAT-1", "developer"); assign("CAT-2", "other");
        var claimed = claim("CAT-1", "developer");
        String execution = claimed.path("task").path("execution").path("executionId").asText();
        String oldToken = runtime.assignmentToken("CAT-1");
        if (initialStatus.equals("BLOCKED")) call("CAT-1", "report_blocker", mapper.createObjectNode());
        if (initialStatus.equals("RUNNING")) call("CAT-1", "report_change_event", mapper.createObjectNode()
                .put("type", "TaskStatusChanged").put("targetStatus", "RUNNING"));
        assertThrows(IllegalStateException.class, () -> operator("CAT-1", "retry_task", mapper.createObjectNode()));
        assertThrows(IllegalStateException.class, () -> claim("CAT-2", "other"));
        var stop = envelope("CAT-1").put("taskExecutionId", execution).put("evidenceRef", "operator:process-exited");
        assertThrows(SecurityException.class, () -> journal.operatorCommand(actor("developer", ProjectRole.PROJECT_MANAGER), "confirm_execution_stopped", stop.deepCopy().put("idempotencyKey", "own-stop")));
        assertThrows(SecurityException.class, () -> operator("CAT-1", "confirm_execution_stopped", stop.deepCopy().put("idempotencyKey", "wrong-stop").put("taskExecutionId", "old-execution")));
        Object result = journal.operatorCommand(manager, "confirm_execution_stopped", stop);
        assertEquals(mapper.readTree(mapper.writeValueAsString(result)), mapper.valueToTree(journal.operatorCommand(manager, "confirm_execution_stopped", stop)));
        assertNotEquals(oldToken, runtime.assignmentToken("CAT-1"));
        restart(); claim("CAT-2", "other");
        operator("CAT-1", "retry_task", mapper.createObjectNode());
        assertThrows(IllegalStateException.class, () -> claim("CAT-1", "developer"));
        assertEquals(base, git(repo, "rev-parse", "main"));
    }

    @Test void missingActorConfigurationIsVisibleAndCannotClaim() throws Exception {
        start(); assign("CAT-1", "unconfigured");
        assertThrows(IllegalStateException.class, () -> claim("CAT-1", "unconfigured"));
        var blockers = mapper.valueToTree(runtime.task("CAT-1")).path("capacityBlockers");
        assertEquals(0, blockers.get(0).path("capacity").asInt(-1));
        assertFalse(Files.exists(repo.getParent().resolve(".refactor-worktrees")));
    }

    @ParameterizedTest @ValueSource(strings={"zero", "fraction", "string", "unknown-env", "missing-env", "path-as-env"})
    void invalidCapacityNeverBindsAPlan(String kind) throws Exception {
        var actors = (ObjectNode) input.path("scheduling").path("actorCapacities");
        var envs = (ObjectNode) input.path("scheduling").path("environmentCapacities");
        switch (kind) {
            case "zero" -> actors.put("developer", 0);
            case "fraction" -> actors.put("developer", 1.5);
            case "string" -> actors.put("developer", "2");
            case "unknown-env" -> envs.put("missing", 1);
            case "missing-env" -> envs.remove("test-db");
            case "path-as-env" -> envs.put("catalog", 1);
        }
        assertThrows(IllegalArgumentException.class, this::start);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM project_plan", Integer.class));
    }

    void start() throws Exception {
        Files.writeString(file, mapper.writeValueAsString(input), StandardCharsets.UTF_8);
        source = new ConfiguredProjectService(file.toString(), jdbc, mapper);
        String fp = source.planScope();
        source.decide(actor("architect", ProjectRole.ARCHITECT), fp, "ARCHITECTURE");
        source.decide(manager, fp, "EXECUTION"); restart();
    }
    void restart() {
        source = new ConfiguredProjectService(file.toString(), jdbc, mapper);
        var audit = new AuditEventRepository(jdbc);
        runtime = new McpProjectRuntime(source, audit, mapper);
        journal = new ProjectMcpController(source, runtime, audit, mapper);
    }
    static Principal actor(String name, ProjectRole role) { return new Principal(name, "legacy-blog", Set.of(role)); }
    ObjectNode envelope(String task) { return mapper.createObjectNode().put("taskId", task).put("expectedVersion", 1).put("idempotencyKey", UUID.randomUUID().toString()); }
    void assign(String task, String owner) { operator(task, "assign_task", mapper.createObjectNode().put("assignee", owner)); }
    JsonNode claim(String task, String owner) { return mapper.valueToTree(journal.claimAssignedTask(actor(owner, ProjectRole.DEVELOPER), envelope(task))); }
    Object operator(String task, String action, ObjectNode extra) {
        var args = envelope(task); args.setAll(extra); return journal.operatorCommand(manager, action, args);
    }
    void call(String task, String action, ObjectNode extra) {
        var args = envelope(task).put("taskToken", runtime.assignmentToken(task)); args.setAll(extra);
        var params = mapper.createObjectNode().put("name", action); params.set("arguments", args);
        var response = mapper.valueToTree(journal.handle(new McpRequest("2.0", mapper.getNodeFactory().numberNode(1), "tools/call", params)).getBody());
        assertFalse(response.has("error"), response::toString);
    }
}
