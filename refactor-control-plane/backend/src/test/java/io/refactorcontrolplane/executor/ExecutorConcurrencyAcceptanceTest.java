package io.refactorcontrolplane.executor;

import static io.refactorcontrolplane.api.ConfiguredProjectApiContractTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import io.refactorcontrolplane.executor.ExecutionProtocol.*;
import io.refactorcontrolplane.governance.LocalIdentityRegistry.Principal;
import io.refactorcontrolplane.governance.ProjectRole;
import io.refactorcontrolplane.mcp.*;
import io.refactorcontrolplane.persistence.AuditEventRepository;
import io.refactorcontrolplane.project.ConfiguredProjectService;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class ExecutorConcurrencyAcceptanceTest {
    @TempDir Path directory;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final Principal developer = actor("developer", ProjectRole.DEVELOPER), manager = actor("manager", ProjectRole.PROJECT_MANAGER);
    Path repo, definition, settingsFile, barrier;
    String base;
    JdbcTemplate jdbc, remoteJdbc;
    ConfiguredProjectService source;
    McpProjectRuntime runtime;
    ProjectMcpController journal;
    ExecutorCoordinator coordinator;
    MockTaskExecutor remoteWorker;
    HttpServer server;
    ExecutorService serverThreads;
    final Map<String, Request> requests = new ConcurrentHashMap<>();
    final Map<String, Result> overrides = new ConcurrentHashMap<>();
    final AtomicInteger puts = new AtomicInteger();
    final AtomicBoolean failCompletion = new AtomicBoolean();
    volatile CountDownLatch queryEntered, releaseQuery;

    @BeforeEach void setup() throws Exception {
        barrier = Files.createDirectories(directory.resolve("barrier"));
        repo = Files.createDirectories(directory.resolve("source"));
        git(repo, "init", "-b", "main"); git(repo, "config", "core.autocrlf", "false");
        for (String path : List.of("one", "two", "three", "four")) {
            Files.createDirectories(repo.resolve(path));
            Files.writeString(repo.resolve(path + "/pom.xml"), "<project/>\n", StandardCharsets.UTF_8);
        }
        git(repo, "add", "."); commit(repo); base = git(repo, "rev-parse", "HEAD");
        var plan = configuration(repo, base, "one");
        var prototype = ((ObjectNode) plan.path("plan").path("workPackages").get(0)).deepCopy();
        var tasks = ((ObjectNode) plan.path("plan")).putArray("workPackages");
        var resources = plan.putObject("resources");
        resources.putObject("test-env").put("kind", "ENVIRONMENT").put("locator", "isolated-env").put("version", "1");
        var taskResources = plan.putObject("taskResources");
        var profiles = plan.putArray("validationProfiles"); var taskProfiles = plan.putObject("taskProfiles");
        String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        String classes = Path.of(ExecutionBarrierCheck.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        var layouts = Map.of("CAT-1", "one", "CAT-2", "two", "CAT-3", "three", "CAT-4", "four", "COLLIDE", "one");
        for (String task : layouts.keySet().stream().sorted().toList()) {
            String layout = layouts.get(task);
            var item = prototype.deepCopy().put("id", task).put("workingDirectory", layout);
            item.putArray("allowedPaths").add(layout); tasks.add(item);
            resources.putObject(task).put("kind", "PATH").put("locator", layout).put("version", "1");
            taskResources.putArray(task).add(task).add("test-env"); taskProfiles.put(task, task);
            var checks = profiles.addObject().put("id", task).put("version", "1").putArray("checks");
            var content = checks.addObject().put("id", "content").put("workingDirectory", layout).put("timeoutSeconds", 65);
            content.set("argv", mapper.valueToTree(List.of(java, "-cp", classes, ExecutionBarrierCheck.class.getName(), barrier.toString(), task)));
            var diff = checks.addObject().put("id", "diff").put("workingDirectory", layout).put("timeoutSeconds", 10);
            diff.set("argv", mapper.valueToTree(List.of("git", "diff", "--check", "HEAD~1", "HEAD")));
            var commands = item.putArray("acceptanceCommands");
            for (var check : checks) {
                var argv = new ArrayList<String>(); check.path("argv").forEach(arg -> argv.add(arg.asText()));
                commands.add(String.join(" ", argv));
            }
        }
        var scheduling = plan.putObject("scheduling"); scheduling.putObject("actorCapacities").put("developer", 3);
        scheduling.putObject("environmentCapacities").put("test-env", 3);
        definition = directory.resolve("project.json"); Files.writeString(definition, plan.toString(), StandardCharsets.UTF_8);
        remoteJdbc = database("remote");
        remoteWorker = new MockTaskExecutor(new ExecutorSettings.Definition("remote", "MOCK", null, null, null,
                Map.of("three/result.txt", "CAT-3")), remoteJdbc, mapper);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverThreads = Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(serverThreads);
        server.createContext("/executions/", exchange -> {
            try {
                if (!"Bearer acceptance-secret".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    exchange.sendResponseHeaders(403, -1); return;
                }
                String id = exchange.getRequestURI().getPath().substring("/executions/".length());
                Result result;
                if (exchange.getRequestMethod().equals("PUT")) {
                    puts.incrementAndGet(); var request = mapper.readValue(exchange.getRequestBody(), Request.class);
                    assertEquals(id, request.execution().executionId());
                    requests.put(id, request); remoteWorker.start(request);
                    exchange.sendResponseHeaders(503, -1); return; // Real work accepted; acknowledgement lost.
                }
                var request = requests.get(id);
                result = overrides.get(id);
                if (result == null && request != null) result = remoteWorker.query(request);
                if (result == null) { exchange.sendResponseHeaders(404, -1); return; }
                byte[] body = mapper.writeValueAsBytes(result);
                if (queryEntered != null) {
                    queryEntered.countDown();
                    try {
                        if (!releaseQuery.await(20, TimeUnit.SECONDS)) throw new java.io.IOException("Query barrier timed out");
                    } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new java.io.IOException(ex); }
                }
                exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
            } finally { exchange.close(); }
        }); server.start();
        settingsFile = directory.resolve("executors.json");
        Files.writeString(settingsFile, mapper.writeValueAsString(List.of(
                Map.of("id", "mock-one", "kind", "MOCK", "outputs", Map.of("one/result.txt", "CAT-1")),
                Map.of("id", "mock-two", "kind", "MOCK", "outputs", Map.of("two/result.txt", "CAT-2")),
                Map.of("id", "remote", "kind", "HTTP", "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort(),
                        "token", "acceptance-secret", "timeoutSeconds", 30))), StandardCharsets.UTF_8);
        jdbc = database("platform"); start();
        source.decide(actor("architect", ProjectRole.ARCHITECT), source.planScope(), "ARCHITECTURE");
        source.decide(manager, source.planScope(), "EXECUTION");
        for (String task : layouts.keySet()) journal.operatorCommand(manager, "assign_task", envelope(task).put("assignee", "developer"));
    }

    JdbcTemplate database(String name) {
        var ds = new DriverManagerDataSource("jdbc:h2:file:" + directory.resolve(name).toString().replace('\\', '/') + ";DB_CLOSE_DELAY=-1;WRITE_DELAY=0", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql"), new ClassPathResource("db/migration/V3__integration_candidates.sql")).execute(ds);
        return new JdbcTemplate(ds);
    }
    void start() {
        source = new ConfiguredProjectService(definition.toString(), jdbc, mapper);
        var audit = new AuditEventRepository(jdbc) {
            @Override public boolean complete(String key, long version, String result) {
                boolean completed = super.complete(key, version, result);
                if (failCompletion.compareAndSet(true, false)) throw new IllegalStateException("Injected lost completion acknowledgement");
                return completed;
            }
        };
        runtime = new McpProjectRuntime(source, audit, mapper); journal = new ProjectMcpController(source, runtime, audit, mapper);
        coordinator = new ExecutorCoordinator(source, runtime, journal, new ExecutorSettings(settingsFile.toString(), mapper), mapper, jdbc, true);
    }
    @AfterEach void close() {
        if (releaseQuery != null) releaseQuery.countDown();
        if (coordinator != null) coordinator.close();
        if (remoteWorker != null) remoteWorker.close();
        if (server != null) server.stop(0);
        if (serverThreads != null) serverThreads.shutdownNow();
        if (jdbc != null) jdbc.execute("SHUTDOWN");
        if (remoteJdbc != null) remoteJdbc.execute("SHUTDOWN");
    }

    @Test void threeRealExecutorsOverlapAndResumeWithoutRedeliveryAfterLostDurableCompletion() throws Exception {
        Map<String, String> ids = new LinkedHashMap<>();
        for (String task : List.of("CAT-1", "CAT-2", "CAT-3")) ids.put(task, claim(task));
        var executorIds = Map.of("CAT-1", "mock-one", "CAT-2", "mock-two", "CAT-3", "remote");
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var starts = new ArrayList<Future<Run>>();
            ids.forEach((task, id) -> starts.add(pool.submit(() -> dispatch(task, id, executorIds.get(task)))));
            for (var future : starts) future.get(15, TimeUnit.SECONDS);
        }
        await(() -> ids.keySet().stream().allMatch(task -> Files.exists(barrier.resolve(task + ".entered"))));
        for (String task : ids.keySet()) {
            var marker = Files.readAllLines(barrier.resolve(task + ".entered"), StandardCharsets.UTF_8);
            assertTrue(ProcessHandle.of(Long.parseLong(marker.get(1))).orElseThrow().isAlive());
            assertFalse(Files.exists(barrier.resolve(task + ".finished")));
            assertEquals(Boolean.TRUE, runtime.task(task).get("capacityHeld"));
        }
        assertEquals(3, runtime.activeExecutorRuns().size());
        String trees = git(repo, "worktree", "list", "--porcelain");
        assertThrows(IllegalStateException.class, () -> claim("CAT-4"));
        assertEquals(trees, git(repo, "worktree", "list", "--porcelain"));
        assertEquals(2, mapper.valueToTree(runtime.task("CAT-4")).path("capacityBlockers").size());
        for (String task : List.of("CAT-1", "CAT-2")) release(task);
        await(() -> {
            coordinator.poll();
            return List.of("CAT-1", "CAT-2").stream().allMatch(task -> runtime.executorRun(ids.get(task)).state().equals("SUCCEEDED"));
        });
        String collision = claim("COLLIDE");
        int before = puts.get();
        assertThrows(IllegalStateException.class, () -> dispatch("COLLIDE", collision, "remote"));
        assertEquals(before, puts.get()); assertNull(runtime.executorRun(collision));
        assertEquals("CLAIMED", runtime.task("COLLIDE").get("status").toString());
        coordinator.close(); jdbc.execute("SHUTDOWN"); jdbc = database("platform"); start();
        assertEquals(Boolean.TRUE, runtime.task("CAT-3").get("capacityHeld"));
        assertEquals(ids.get("CAT-3"), execution("CAT-3").executionId());
        dispatch("CAT-3", ids.get("CAT-3"), "remote"); assertEquals(1, puts.get());
        release("CAT-3");
        await(() -> "SUCCEEDED".equals(remoteWorker.query(requests.get(ids.get("CAT-3"))).state()));
        // Crash boundary: durable journal completion succeeds but its acknowledgement is lost.
        failCompletion.set(true);
        assertThrows(IllegalStateException.class, () -> coordinator.refresh(developer, ids.get("CAT-3")));
        coordinator.close(); jdbc.execute("SHUTDOWN"); jdbc = database("platform"); start();
        var rows = new ArrayList<Map<String, Object>>();
        for (String task : ids.keySet()) {
            var result = coordinator.refresh(developer, ids.get(task));
            assertEquals("SUCCEEDED", result.state()); assertEquals(2, result.result().checks().size());
            assertEquals("SUBMITTED", runtime.task(task).get("status").toString());
            var execution = execution(task); Path tree = Path.of(execution.path());
            assertEquals(execution.commitSha(), git(tree, "rev-parse", "HEAD"));
            assertEquals(base, git(tree, "rev-parse", "HEAD~1"));
            assertEquals(1, git(tree, "rev-list", "--count", base + "..HEAD").lines().mapToInt(Integer::parseInt).sum());
            var begun = Files.readAllLines(barrier.resolve(task + ".entered"), StandardCharsets.UTF_8);
            rows.add(Map.of("task", task, "executionId", execution.executionId(), "branch", execution.branch(), "commit", execution.commitSha(),
                    "checkStarted", begun.get(0), "checkFinished", Files.readString(barrier.resolve(task + ".finished"), StandardCharsets.UTF_8), "checkPid", begun.get(1)));
        }
        Instant latestStart = rows.stream().map(row -> Instant.parse((String) row.get("checkStarted"))).max(Instant::compareTo).orElseThrow();
        Instant earliestFinish = rows.stream().map(row -> Instant.parse((String) row.get("checkFinished"))).min(Instant::compareTo).orElseThrow();
        assertTrue(latestStart.isBefore(earliestFinish));
        assertEquals(3, rows.stream().map(row -> row.get("branch")).distinct().count());
        assertEquals(3, rows.stream().map(row -> row.get("checkPid")).distinct().count());
        assertEquals(1, puts.get()); assertEquals(base, git(repo, "rev-parse", "main"));
        Path evidence = Path.of("target", "phase8c-evidence.json");
        Files.writeString(evidence, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("verifiedAt", Instant.now().toString(),
                "baseCommit", base, "mainAfter", git(repo, "rev-parse", "main"), "httpPuts", puts.get(), "executions", rows,
                "overlapMillis", Duration.between(latestStart, earliestFinish).toMillis(), "recovery", "file-H2 reopen plus lost durable completion acknowledgement")), StandardCharsets.UTF_8);
    }

    @Test void unauthorizedHttpCommitCannotSubmitAndStoppedCredentialsCannotAffectRetry() throws Exception {
        String id = claim("CAT-3"), token = runtime.assignmentToken("CAT-3");
        dispatch("CAT-3", id, "remote"); release("CAT-3");
        await(() -> remoteWorker.query(requests.get(id)).state().equals("SUCCEEDED"));
        Path tree = Path.of(execution("CAT-3").path());
        Files.createDirectory(tree.resolve("private-data"));
        Files.writeString(tree.resolve("private-data/unauthorized.txt"), "unauthorized", StandardCharsets.UTF_8);
        git(tree, "add", "."); commit(tree);
        var accepted = remoteWorker.query(requests.get(id));
        overrides.put(id, new Result(id, accepted.requestHash(), 2, "SUCCEEDED", git(tree, "rev-parse", "HEAD"), accepted.checks()));
        int failures = jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE status='FAILED'", Integer.class);
        assertThrows(SecurityException.class, () -> coordinator.refresh(developer, id));
        assertEquals(failures + 1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE status='FAILED'", Integer.class));
        assertNull(execution("CAT-3").commitSha()); assertEquals(Boolean.TRUE, runtime.task("CAT-3").get("capacityHeld"));
        // Capture an old successful HTTP response while its execution is still current, then deliver it late.
        queryEntered = new CountDownLatch(1); releaseQuery = new CountDownLatch(1);
        var late = CompletableFuture.supplyAsync(() -> coordinator.refresh(developer, id));
        String next;
        try {
            assertTrue(queryEntered.await(5, TimeUnit.SECONDS));
            journal.operatorCommand(manager, "confirm_execution_stopped", envelope("CAT-3").put("taskExecutionId", id).put("evidenceRef", "worker-terminal-confirmed"));
            journal.operatorCommand(manager, "retry_task", envelope("CAT-3"));
            next = claim("CAT-3");
            assertFalse(late.isDone(), "The old HTTP query must still be in flight when its response is released");
        } finally { releaseQuery.countDown(); }
        assertEquals("STOPPED", late.get(15, TimeUnit.SECONDS).state());
        queryEntered = null; releaseQuery = null;
        assertNotEquals(id, next); assertEquals("STOPPED", coordinator.refresh(developer, id).state());
        var params = mapper.createObjectNode().put("name", "report_blocker");
        params.set("arguments", envelope("CAT-3").put("taskToken", token));
        var denied = mapper.valueToTree(journal.handle(new McpRequest("2.0", mapper.getNodeFactory().numberNode(1), "tools/call", params)).getBody());
        assertEquals(-32002, denied.path("error").path("code").asInt());
        assertEquals(next, execution("CAT-3").executionId());
        assertEquals("CLAIMED", runtime.task("CAT-3").get("status").toString());
        assertEquals(1, puts.get()); assertEquals(base, git(repo, "rev-parse", "main"));
    }

    static Principal actor(String id, ProjectRole role) { return new Principal(id, "legacy-blog", Set.of(role)); }
    ObjectNode envelope(String task) { return mapper.createObjectNode().put("taskId", task).put("expectedVersion", runtime.contextVersion()).put("idempotencyKey", UUID.randomUUID().toString()); }
    String claim(String task) { journal.claimAssignedTask(developer, envelope(task)); return execution(task).executionId(); }
    McpProjectRuntime.Execution execution(String task) { return (McpProjectRuntime.Execution) runtime.task(task).get("execution"); }
    Run dispatch(String task, String id, String executor) { return coordinator.dispatch(developer, id, task, executor, runtime.contextVersion(), UUID.randomUUID().toString()); }
    void release(String task) throws Exception { Files.writeString(barrier.resolve(task + ".release"), "release", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW); }
    void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) fail("Timed out: " + runtime.activeExecutorRuns());
            Thread.sleep(100);
        }
    }
}
