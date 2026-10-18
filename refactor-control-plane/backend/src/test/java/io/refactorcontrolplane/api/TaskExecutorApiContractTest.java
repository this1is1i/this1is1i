package io.refactorcontrolplane.api;

import static io.refactorcontrolplane.api.ConfiguredProjectApiContractTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import io.refactorcontrolplane.governance.LocalIdentityRegistry.Principal;
import io.refactorcontrolplane.governance.ProjectRole;
import io.refactorcontrolplane.mcp.*;
import io.refactorcontrolplane.project.ConfiguredProjectService;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties={"control-plane.project.mode=configured", "control-plane.identity-file=classpath:review-identities.json",
        "spring.datasource.url=jdbc:h2:mem:executor-api", "control-plane.executors.auto-poll=false"})
@AutoConfigureMockMvc
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class TaskExecutorApiContractTest {
    @TempDir static Path directory;
    static Path repo;
    static String base;
    static HttpServer remote;
    static AtomicInteger posts = new AtomicInteger();
    static AtomicReference<JsonNode> ticket = new AtomicReference<>(), response = new AtomicReference<>();
    static volatile java.util.concurrent.CountDownLatch putEntered, releasePut;
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    @Autowired MockMvc mvc;
    @Autowired ConfiguredProjectService source;
    @Autowired McpProjectRuntime runtime;
    @Autowired ProjectMcpController journal;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) throws Exception {
        repo = Files.createDirectories(directory.resolve("source")); git(repo, "init", "-b", "main");
        for (String path : List.of("src", "remote", "stopped")) {
            Files.createDirectories(repo.resolve(path)); Files.writeString(repo.resolve(path + "/pom.xml"), "<project/>", StandardCharsets.UTF_8);
        }
        git(repo, "add", "."); commit(repo); base = git(repo, "rev-parse", "HEAD");
        var plan = configuration(repo, base, "src");
        var task = ((ObjectNode) plan.path("plan").path("workPackages").get(0)).deepCopy();
        task.put("id", "HTTP-1").putArray("allowedPaths").add("remote");
        ((ArrayNode) plan.path("plan").path("workPackages")).add(task);
        ((ObjectNode) plan.path("resources")).putObject("remote").put("kind", "PATH").put("locator", "remote").put("version", "1");
        ((ObjectNode) plan.path("taskResources")).putArray("HTTP-1").add("remote");
        ((ObjectNode) plan.path("taskProfiles")).put("HTTP-1", "catalog-check");
        var stopped = task.deepCopy(); stopped.put("id", "STOP-1").putArray("allowedPaths").add("stopped");
        ((ArrayNode) plan.path("plan").path("workPackages")).add(stopped);
        ((ObjectNode) plan.path("resources")).putObject("stopped").put("kind", "PATH").put("locator", "stopped").put("version", "1");
        ((ObjectNode) plan.path("taskResources")).putArray("STOP-1").add("stopped");
        ((ObjectNode) plan.path("taskProfiles")).put("STOP-1", "catalog-check");
        var scheduling = plan.putObject("scheduling"); scheduling.putObject("actorCapacities").put("developer", 2);
        scheduling.putObject("environmentCapacities");
        Path file = directory.resolve("project.json"); Files.writeString(file, plan.toString(), StandardCharsets.UTF_8);
        remote = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        remote.createContext("/executions", exchange -> {
            byte[] payload;
            int code;
            if (exchange.getRequestMethod().equals("PUT")) {
                posts.incrementAndGet(); ticket.set(JSON.readTree(exchange.getRequestBody()));
                if (putEntered != null) {
                    putEntered.countDown();
                    try { releasePut.await(10, java.util.concurrent.TimeUnit.SECONDS); }
                    catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                }
                code = 503; payload = "{}".getBytes(StandardCharsets.UTF_8); // Accepted remotely, acknowledgement lost.
            } else {
                code = response.get() == null ? 404 : 200;
                payload = (response.get() == null ? "{}" : response.get().toString()).getBytes(StandardCharsets.UTF_8);
            }
            exchange.sendResponseHeaders(code, payload.length); exchange.getResponseBody().write(payload); exchange.close();
        }); remote.start();
        Path executors = directory.resolve("executors.json");
        Files.writeString(executors, JSON.writeValueAsString(List.of(
                Map.of("id", "mock", "kind", "MOCK", "outputs", Map.of("src/result.txt", "actual mock output")),
                Map.of("id", "http", "kind", "HTTP", "baseUrl", "http://127.0.0.1:" + remote.getAddress().getPort(), "token", "local-test-secret", "timeoutSeconds", 2))), StandardCharsets.UTF_8);
        properties.add("control-plane.project.definition-file", () -> file.toString());
        properties.add("control-plane.executors.file", () -> executors.toString());
    }
    @AfterAll static void closeServer() { if (remote != null) remote.stop(0); }
    @BeforeEach void approve() {
        if (!source.approved()) {
            source.decide(actor("architect", ProjectRole.ARCHITECT), source.planScope(), "ARCHITECTURE");
            source.decide(actor("manager", ProjectRole.PROJECT_MANAGER), source.planScope(), "EXECUTION");
        }
    }

    @Test void mockActuallyWritesValidatesCommitsAndUsesExistingTaskSubmission() throws Exception {
        String id = claim("CAT-1");
        var body = command("CAT-1").put("executorId", "mock");
        mvc.perform(post("/api/v1/executions/" + id + "/dispatch").contentType("application/json").content(body.toString()))
                .andExpect(status().isForbidden());
        JsonNode run = dispatch(id, body);
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
        while (!run.path("state").asText().equals("SUCCEEDED") && System.nanoTime() < deadline) {
            Thread.sleep(100); run = refresh(id);
        }
        assertEquals("SUCCEEDED", run.path("state").asText(), run.toString());
        assertEquals("SUBMITTED", runtime.task("CAT-1").get("status").toString());
        Path tree = Path.of(((McpProjectRuntime.Execution) runtime.task("CAT-1").get("execution")).path());
        assertEquals("actual mock output", Files.readString(tree.resolve("src/result.txt"), StandardCharsets.UTF_8));
        assertNotEquals(base, git(tree, "rev-parse", "HEAD"));
        assertEquals(base, git(repo, "rev-parse", "main"));
        assertEquals("SUCCEEDED", dispatch(id, body).path("state").asText());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mock_executor_job WHERE execution_id=?", Integer.class, id));
    }

    @Test void uncertainHttpDeliveryNeverResubmitsAndAcceptsOnlyMatchingVerifiedResult() throws Exception {
        int before = posts.get();
        String id = claim("HTTP-1");
        var body = command("HTTP-1").put("executorId", "http");
        assertEquals("UNKNOWN", dispatch(id, body).path("state").asText());
        dispatch(id, body); refresh(id); assertEquals(before + 1, posts.get());
        assertFalse(ticket.get().has("taskToken"));
        var recovered = new McpProjectRuntime(source, new io.refactorcontrolplane.persistence.AuditEventRepository(jdbc), JSON);
        assertEquals("UNKNOWN", recovered.executorRun(id).state());
        assertEquals(ticket.get().path("requestHash").asText(), recovered.executorRun(id).request().requestHash());
        var alive = JSON.createObjectNode().put("executionId", id).put("requestHash", ticket.get().path("requestHash").asText())
                .put("sequence", 1).put("state", "RUNNING");
        response.set(alive); assertEquals("RUNNING", refresh(id).path("state").asText());
        response.set(null); assertEquals("UNKNOWN", refresh(id).path("state").asText());
        response.set(alive); assertEquals("RUNNING", refresh(id).path("state").asText());
        Path tree = Path.of(ticket.get().path("execution").path("path").asText());
        Files.writeString(tree.resolve("remote/result.txt"), "remote output", StandardCharsets.UTF_8);
        git(tree, "add", "."); commit(tree);
        var result = JSON.createObjectNode().put("executionId", id).put("requestHash", "wrong-binding")
                .put("sequence", 2).put("state", "SUCCEEDED").put("commitSha", git(tree, "rev-parse", "HEAD"));
        result.putArray("checks").addObject().put("id", "diff").put("exitCode", 0).put("timedOut", false).put("truncated", false);
        response.set(result);
        mvc.perform(post("/api/v1/executions/" + id + "/refresh").header("Authorization", "Bearer developer-test-token"))
                .andExpect(status().isConflict());
        assertNull(((McpProjectRuntime.Execution) runtime.task("HTTP-1").get("execution")).commitSha());
        result.put("requestHash", ticket.get().path("requestHash").asText());
        // Recovery after the worker deadline drops expired leases, but retains its bound execution.
        var snapshot = runtime.snapshot();
        var now = java.time.Instant.now();
        var competitor = new io.refactorcontrolplane.ownership.PathLease(UUID.randomUUID(), "other", "competing-execution", "remote",
                io.refactorcontrolplane.ownership.OwnershipMode.EXCLUSIVE_WRITE, 1, now, now, now.plusSeconds(60));
        runtime.restore(new McpProjectRuntime.Projection(snapshot.project(), List.of(competitor), snapshot.executions(),
                snapshot.assignmentOwners(), snapshot.assignmentEpochs(), snapshot.stoppedExecutions(), snapshot.executorRuns()));
        mvc.perform(post("/api/v1/executions/" + id + "/refresh").header("Authorization", "Bearer developer-test-token"))
                .andExpect(status().isConflict());
        assertEquals(List.of(competitor), runtime.snapshot().leases());
        assertNull(((McpProjectRuntime.Execution) runtime.task("HTTP-1").get("execution")).commitSha());
        runtime.restore(new McpProjectRuntime.Projection(snapshot.project(), List.of(), snapshot.executions(),
                snapshot.assignmentOwners(), snapshot.assignmentEpochs(), snapshot.stoppedExecutions(), snapshot.executorRuns()));
        assertEquals("SUCCEEDED", refresh(id).path("state").asText());
        assertEquals(before + 1, posts.get()); assertEquals(base, git(repo, "rev-parse", "main"));
        String audit = jdbc.queryForList("SELECT payload FROM audit_event", String.class).toString();
        assertFalse(audit.contains("local-test-secret"));
    }
    @Test void inFlightDeliveryCannotRaceWithStopAndLateResultCannotReplaceNewExecution() throws Exception {
        String id = claim("STOP-1");
        putEntered = new java.util.concurrent.CountDownLatch(1); releasePut = new java.util.concurrent.CountDownLatch(1);
        var sending = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try { return dispatch(id, command("STOP-1").put("executorId", "http")); }
            catch (Exception ex) { throw new RuntimeException(ex); }
        });
        var stop = command("STOP-1").put("taskExecutionId", id).put("evidenceRef", "remote stopped");
        try {
            assertTrue(putEntered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () -> journal.operatorCommand(actor("manager", ProjectRole.PROJECT_MANAGER), "confirm_execution_stopped", stop));
        } finally { releasePut.countDown(); sending.get(10, java.util.concurrent.TimeUnit.SECONDS); putEntered = null; releasePut = null; }
        stop.put("idempotencyKey", UUID.randomUUID().toString());
        journal.operatorCommand(actor("manager", ProjectRole.PROJECT_MANAGER), "confirm_execution_stopped", stop);
        journal.operatorCommand(actor("manager", ProjectRole.PROJECT_MANAGER), "retry_task", command("STOP-1"));
        String next = JSON.valueToTree(journal.claimAssignedTask(actor("developer", ProjectRole.DEVELOPER), command("STOP-1")))
                .path("task").path("execution").path("executionId").asText();
        assertNotEquals(id, next);
        assertEquals("STOPPED", refresh(id).path("state").asText());
        assertEquals(next, ((McpProjectRuntime.Execution) runtime.task("STOP-1").get("execution")).executionId());
        // No worker started for the new claim; release it so test ordering cannot exhaust actor capacity.
        journal.operatorCommand(actor("manager", ProjectRole.PROJECT_MANAGER), "confirm_execution_stopped",
                command("STOP-1").put("taskExecutionId", next).put("evidenceRef", "new claim not dispatched"));
    }
    static Principal actor(String id, ProjectRole role) { return new Principal(id, "legacy-blog", Set.of(role)); }
    ObjectNode command(String task) { return JSON.createObjectNode().put("taskId", task).put("expectedVersion", runtime.contextVersion()).put("idempotencyKey", UUID.randomUUID().toString()); }
    String claim(String task) {
        journal.operatorCommand(actor("manager", ProjectRole.PROJECT_MANAGER), "assign_task", command(task).put("assignee", "developer"));
        return JSON.valueToTree(journal.claimAssignedTask(actor("developer", ProjectRole.DEVELOPER), command(task)))
                .path("task").path("execution").path("executionId").asText();
    }
    JsonNode dispatch(String id, ObjectNode body) throws Exception {
        return JSON.readTree(mvc.perform(post("/api/v1/executions/" + id + "/dispatch").header("Authorization", "Bearer developer-test-token")
                .contentType("application/json").content(body.toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    JsonNode refresh(String id) throws Exception {
        return JSON.readTree(mvc.perform(post("/api/v1/executions/" + id + "/refresh").header("Authorization", "Bearer developer-test-token"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
}
