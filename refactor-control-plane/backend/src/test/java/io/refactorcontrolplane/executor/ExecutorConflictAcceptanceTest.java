package io.refactorcontrolplane.executor;

import static io.refactorcontrolplane.api.ConfiguredProjectApiContractTest.*;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import io.refactorcontrolplane.executor.ExecutionProtocol.*;
import io.refactorcontrolplane.governance.*;
import io.refactorcontrolplane.governance.LocalIdentityRegistry.Principal;
import io.refactorcontrolplane.integration.*;
import io.refactorcontrolplane.mcp.*;
import io.refactorcontrolplane.model.*;
import io.refactorcontrolplane.persistence.AuditEventRepository;
import io.refactorcontrolplane.project.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class ExecutorConflictAcceptanceTest {
    @TempDir Path directory;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final Principal developer = actor("developer", ProjectRole.DEVELOPER), manager = actor("manager", ProjectRole.PROJECT_MANAGER),
            lead = actor("lead", ProjectRole.ENGINEERING_LEAD), qa = actor("qa", ProjectRole.QA_LEAD);
    Path repo, definition, settings;
    String base;
    JdbcTemplate jdbc;
    ConfiguredProjectService source;
    McpProjectRuntime runtime;
    ProjectMcpController journal;
    ExecutorCoordinator coordinator;
    IntegrationService integration;
    ExecutionModelEvidence models;
    EvidenceStore evidence;
    HttpServer server;
    final Map<String, Result> results = new HashMap<>();
    final AtomicInteger puts = new AtomicInteger(), calls = new AtomicInteger();
    final AtomicBoolean loseCompletion = new AtomicBoolean();
    boolean failBeforeCompletion;
    final AtomicReference<Throwable> workerFailure = new AtomicReference<>();
    final List<String> longResources = new ArrayList<>();

    @BeforeEach void setup(TestInfo info) throws Exception {
        repo = Files.createDirectories(directory.resolve("source"));
        git(repo, "init", "-b", "main"); git(repo, "config", "core.autocrlf", "false");
        for (var entry : Map.of("api/value.txt", "1", "client/expected.txt", "1", "contracts/api.txt", "v1", "db/schema.sql", "CREATE TABLE old_table(id INT);").entrySet()) {
            Files.createDirectories(repo.resolve(entry.getKey()).getParent()); Files.writeString(repo.resolve(entry.getKey()), entry.getValue(), StandardCharsets.UTF_8);
        }
        Files.writeString(repo.resolve("Check.java"), "import java.nio.file.*; class Check { public static void main(String[] a) throws Exception { if (!Files.readString(Path.of(\"api/value.txt\")).equals(Files.readString(Path.of(\"client/expected.txt\")))) throw new AssertionError(\"behavior incompatible\"); } }", StandardCharsets.UTF_8);
        git(repo, "add", "."); commit(repo); base = git(repo, "rev-parse", "HEAD");
        var plan = configuration(repo, base, ".");
        ((ObjectNode) plan.path("plan")).putArray("criticalPath").add("A");
        var tasks = ((ObjectNode) plan.path("plan")).putArray("workPackages");
        var resources = plan.putObject("resources"); var taskResources = plan.putObject("taskResources"); var profiles = plan.putObject("taskProfiles");
        for (var entry : Map.of("A", "api", "B", "client", "C", "api", "CONTRACT", "contracts", "SCHEMA", "db").entrySet()) {
            var task = tasks.addObject().put("id", entry.getKey()).put("title", entry.getKey()).put("role", "DEVELOPER").put("estimatedHours", 1).put("workingDirectory", ".");
            task.putArray("dependencies"); task.putArray("allowedPaths").add(entry.getValue()); task.putArray("acceptanceCommands").add("git diff --check");
            resources.putObject(entry.getKey()).put("kind", "PATH").put("locator", entry.getValue()).put("version", "1");
            taskResources.putArray(entry.getKey()).add(entry.getKey()); profiles.put(entry.getKey(), "local");
        }
        for (String asset : List.of("API_CONTRACT", "DB_SCHEMA")) {
            resources.putObject(asset).put("kind", asset).put("locator", asset.equals("API_CONTRACT") ? "contracts/api.txt" : "db/schema.sql").put("version", "1");
            for (String task : List.of("A", "B", asset.equals("API_CONTRACT") ? "CONTRACT" : "SCHEMA")) ((ArrayNode) taskResources.path(task)).add(asset);
        }
        var local = new IntegrationProfiles.Check("diff", List.of("git", "diff", "--check"), ".", 10);
        String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        plan.set("validationProfiles", mapper.valueToTree(List.of(new IntegrationProfiles.Profile("local", "1", List.of(local)),
                new IntegrationProfiles.Profile("combined", "1", List.of(local, new IntegrationProfiles.Check("behavior", List.of(java, "Check.java"), ".", 30))))));
        plan.putObject("scheduling").putObject("actorCapacities").put("developer", 5);
        ((ObjectNode) plan.path("scheduling")).putObject("environmentCapacities");
        if (info.getTestMethod().orElseThrow().getName().equals("oversizedSemanticEvidenceIsRejectedBeforeJournalWithoutPoisoningRecovery")) {
            var prototype = ((ObjectNode) tasks.get(0)).deepCopy(); prototype.putArray("allowedPaths").add("client");
            for (int i = 0; i < 95; i++) {
                String prefix = "EXTRA" + i, id = prefix + "x".repeat(100 - prefix.length());
                tasks.add(prototype.deepCopy().put("id", id).put("title", "extra")); taskResources.putArray(id).add("B"); profiles.put(id, "local");
            }
            for (int i = 0; i < 20; i++) {
                String prefix = "资源" + i, id = prefix + "重".repeat(1000 - prefix.length()); longResources.add(id);
                resources.putObject(id).put("kind", "API_CONTRACT").put("locator", "contract-" + i).put("version", "1");
                ((ArrayNode) taskResources.path("CONTRACT")).add(id);
            }
        }
        definition = directory.resolve("project.json"); Files.writeString(definition, plan.toString(), StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/executions/", exchange -> {
            try {
                Result result;
                if (exchange.getRequestMethod().equals("PUT")) {
                    puts.incrementAndGet(); var request = mapper.readValue(exchange.getRequestBody(), Request.class);
                    result = work(request); results.put(result.executionId(), result);
                    result = new Result(result.executionId(), result.requestHash(), 1, "RUNNING", null, List.of());
                } else result = results.get(exchange.getRequestURI().getPath().substring("/executions/".length()));
                byte[] body = mapper.writeValueAsBytes(result); exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
            } catch (Throwable ex) { workerFailure.set(ex); exchange.sendResponseHeaders(500, -1); }
            finally { exchange.close(); }
        }); server.start();
        settings = directory.resolve("executors.json"); Files.writeString(settings, mapper.writeValueAsString(List.of(Map.of("id", "http", "kind", "HTTP", "baseUrl",
                "http://127.0.0.1:" + server.getAddress().getPort(), "token", "local-only-secret", "timeoutSeconds", 30))), StandardCharsets.UTF_8);
        open(); source.decide(actor("architect", ProjectRole.ARCHITECT), source.planScope(), "ARCHITECTURE"); source.decide(manager, source.planScope(), "EXECUTION");
        for (String task : List.of("A", "B", "C", "CONTRACT", "SCHEMA")) journal.operatorCommand(manager, "assign_task", envelope(task).put("assignee", "developer"));
    }
    void open() {
        var ds = new DriverManagerDataSource("jdbc:h2:file:" + directory.resolve("platform").toString().replace('\\', '/') + ";DB_CLOSE_DELAY=-1;WRITE_DELAY=0", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql"), new ClassPathResource("db/migration/V3__integration_candidates.sql")).execute(ds);
        jdbc = new JdbcTemplate(ds); source = new ConfiguredProjectService(definition.toString(), jdbc, mapper);
        evidence = new EvidenceStore(jdbc, mapper); models = new ExecutionModelEvidence(evidence, mapper);
        var store = new IntegrationStore(jdbc, mapper, new DataSourceTransactionManager(ds)); var profiles = new IntegrationProfiles("", mapper, source);
        var gates = new GateEvidencePolicy(evidence, store, profiles, source, mapper, "refs/heads/main");
        var audit = new AuditEventRepository(jdbc) {
            @Override public boolean complete(String key, long version, String result) {
                if (failBeforeCompletion && loseCompletion.compareAndSet(true, false)) throw new IllegalStateException("Completion not recorded");
                boolean done = super.complete(key, version, result);
                if (loseCompletion.compareAndSet(true, false)) throw new IllegalStateException("Lost durable completion response");
                return done;
            }
        };
        runtime = new McpProjectRuntime(source, audit, mapper, gates, evidence); journal = new ProjectMcpController(source, runtime, audit, mapper);
        integration = new IntegrationService(source, runtime, store, profiles, "refs/heads/main");
        coordinator = new ExecutorCoordinator(source, runtime, journal, new ExecutorSettings(settings.toString(), mapper), mapper, jdbc, false);
    }
    Result work(Request request) throws Exception {
        String task = request.execution().taskId(); String path = switch (task) {
            case "A", "C" -> "api/value.txt"; case "B" -> "client/expected.txt"; case "CONTRACT" -> "contracts/api.txt"; default -> "db/schema.sql";
        };
        String value = switch (task) { case "A" -> "2"; case "B", "C" -> "3"; case "CONTRACT" -> "v2"; default -> "CREATE TABLE new_table(id INT);"; };
        Path tree = Path.of(request.execution().path());
        var input = new ModelRequest("acceptance-v1", Files.readString(tree.resolve(path), StandardCharsets.UTF_8), value, List.of("write_file", "unused_tool"));
        var output = new MockModelGateway().execute(input); calls.incrementAndGet();
        String generated = output.output().substring(output.output().indexOf("# instruction: ") + "# instruction: ".length());
        Files.writeString(tree.resolve(path), generated, StandardCharsets.UTF_8); git(tree, "add", "."); commit(tree);
        var checks = new ArrayList<CheckResult>();
        for (var check : request.checks()) {
            var result = ControlledProcess.run(tree.resolve(check.workingDirectory()), check.argv(), Map.of(), new byte[0], check.timeoutSeconds(), 65536);
            assertTrue(result.passed()); checks.add(new CheckResult(check.id(), result.exitCode(), result.timedOut(), result.truncated()));
        }
        var receipt = new ExecutorModelRun("model-1", output.audit().model(), input.promptVersion(), ExecutorSettings.hash(mapper.writeValueAsString(input)),
                output.audit().contextHash(), output.audit().outputHash(), output.audit().totalTokens(), input.tools(),
                List.of(new ExecutorModelRun.ToolCall("tool-1", "write_file", ExecutorSettings.hash(path + "\n" + generated), ExecutorSettings.hash(generated), "SUCCEEDED")));
        return new Result(request.execution().executionId(), request.requestHash(), 2, "SUCCEEDED", git(tree, "rev-parse", "HEAD"), checks, List.of(receipt));
    }
    @AfterEach void close() { if (coordinator != null) coordinator.close(); if (server != null) server.stop(0); if (jdbc != null) jdbc.execute("SHUTDOWN"); }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void modelReceiptsRecoverAfterLostCompletionAndDetectTamperingWithoutRepeatingModelOrWork(boolean beforeCompletion) throws Exception {
        String id = dispatch("A"); failBeforeCompletion = beforeCompletion; loseCompletion.set(true);
        assertThrows(IllegalStateException.class, () -> coordinator.refresh(developer, id));
        coordinator.close(); jdbc.execute("SHUTDOWN"); open();
        var recovered = coordinator.refresh(developer, id); assertEquals("SUCCEEDED", recovered.state());
        var receipt = evidence.read(source.planScope(), recovered.modelEvidenceRef());
        assertEquals(id, receipt.binding().executionId()); assertEquals(results.get(id).commitSha(), receipt.binding().commitSha());
        assertEquals(2, receipt.content().path("modelRuns").get(0).path("requestedTools").size());
        assertEquals(1, receipt.content().path("modelRuns").get(0).path("toolCalls").size());
        assertNotEquals(receipt.content().path("modelRuns").get(0).path("inputHash"), receipt.content().path("modelRuns").get(0).path("contextHash"));
        assertEquals(recovered, coordinator.dispatch(developer, id, "A", "http", 1, "retry-dispatch"));
        assertEquals(1, puts.get()); assertEquals(1, calls.get());
        var snapshot = runtime.snapshot(); var changedRuns = new HashMap<>(snapshot.executorRuns());
        var withoutModels = new Result(recovered.result().executionId(), recovered.result().requestHash(), recovered.result().sequence(), "SUCCEEDED", recovered.result().commitSha(), recovered.result().checks());
        changedRuns.put(id, new Run(recovered.request(), "SUCCEEDED", withoutModels, recovered.observedAt(), recovered.modelEvidenceRef()));
        runtime.restore(new McpProjectRuntime.Projection(snapshot.project(), snapshot.leases(), snapshot.executions(), snapshot.assignmentOwners(), snapshot.assignmentEpochs(), snapshot.stoppedExecutions(), changedRuns, snapshot.gateEvidence()));
        assertThrows(IllegalStateException.class, () -> coordinator.read(developer, id), "Removing receipts must not disable evidence verification");
        runtime.restore(snapshot);
        var repeated = envelope("A").put("taskExecutionId", id); repeated.set("result", mapper.valueToTree(results.get(id)));
        assertEquals(recovered.modelEvidenceRef(), mapper.valueToTree(journal.executorCommand(developer, "observe", repeated)).path("modelEvidenceRef").asText());
        String payload = jdbc.queryForObject("SELECT payload FROM evidence_record WHERE id=?", String.class, receipt.id());
        jdbc.update("UPDATE evidence_record SET payload='{}' WHERE id=?", receipt.id());
        assertThrows(IllegalStateException.class, () -> coordinator.read(developer, id));
        assertThrows(IllegalStateException.class, () -> runtime.workbenchSnapshot());
        jdbc.update("UPDATE evidence_record SET payload=? WHERE id=?", payload, receipt.id());
        assertEquals(recovered, coordinator.read(developer, id));
        assertFalse(jdbc.queryForList("SELECT payload FROM audit_event", String.class).toString().contains("local-only-secret"));
        assertEquals(base, git(repo, "rev-parse", "main"));
        report("model-recovery-" + beforeCompletion, Map.of("executionId", id, "commit", recovered.result().commitSha(), "modelEvidenceRef", receipt.id(), "httpPuts", puts.get(), "modelCalls", calls.get()));
    }
    @Test void excessiveModelEvidenceIsRejectedBeforeJournalOrSubmission() {
        String id = dispatch("A"); var original = results.get(id);
        var receipts = new ArrayList<ExecutorModelRun>();
        for (int i = 0; i < 20; i++) receipts.add(new ExecutorModelRun("model-" + i, "model", "v1", "1".repeat(64), "2".repeat(64), "3".repeat(64), 0,
                java.util.Collections.nCopies(50, "t".repeat(100)), List.of()));
        var excessive = new Result(id, original.requestHash(), 2, "SUCCEEDED", original.commitSha(), original.checks(), receipts);
        var args = envelope("A").put("taskExecutionId", id); args.set("result", mapper.valueToTree(excessive));
        int rows = jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Integer.class);
        assertThrows(IllegalArgumentException.class, () -> journal.executorCommand(developer, "observe", args));
        assertEquals(rows, jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Integer.class));
        assertNull(((McpProjectRuntime.Execution) runtime.task("A").get("execution")).commitSha());
    }
    @Test void oversizedSemanticEvidenceIsRejectedBeforeJournalWithoutPoisoningRecovery() throws Exception {
        assertTrue(Files.size(definition) <= 262144);
        String id = dispatch("CONTRACT"); var run = coordinator.refresh(developer, id);
        var args = envelope("CONTRACT").put("taskExecutionId", id).put("commitSha", run.result().commitSha()).put("assetVersion", 2);
        args.set("resourceIds", mapper.valueToTree(longResources)); args.putArray("artifactPaths").add("contracts/api.txt");
        args.set("affectedTaskIds", mapper.valueToTree(source.overview().plan().workPackages().stream().map(item -> item.id()).toList()));
        int rows = jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Integer.class);
        args.put("taskToken", runtime.assignmentToken("CONTRACT"));
        var params = mapper.createObjectNode().put("name", "publish_contract_change"); params.set("arguments", args);
        var response = mapper.valueToTree(journal.handle(new McpRequest("2.0", mapper.getNodeFactory().numberNode(1), "tools/call", params)).getBody());
        assertTrue(response.has("error"));
        assertEquals(rows, jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Integer.class), "Predictable content limits must reject before PENDING");
        coordinator.close(); jdbc.execute("SHUTDOWN"); open();
        assertEquals(1, runtime.contextVersion()); assertEquals("SUBMITTED", runtime.task("CONTRACT").get("status").toString());
    }
    @Test void samePathLeaseBlocksDeliveryAndActualSubmittedBranchesHaveTextConflict() throws Exception {
        String a = dispatch("A"); String c = claim("C");
        assertThrows(IllegalStateException.class, () -> coordinator.dispatch(developer, c, "C", "http", 1, "blocked"));
        assertEquals(1, puts.get()); coordinator.refresh(developer, a); release("A");
        coordinator.dispatch(developer, c, "C", "http", 1, "dispatch-c"); assertNull(workerFailure.get()); coordinator.refresh(developer, c);
        var candidate = candidate(List.of("A", "C"), "local");
        assertEquals("CONFLICTED", candidate.effectiveState()); assertTrue(mapper.valueToTree(candidate).path("candidate").path("assembly").path("conflictingPaths").toString().contains("api/value.txt"));
        assertThrows(IllegalStateException.class, () -> integration.validate(qa, candidate.candidate().plan().id(), new IntegrationService.Validate("bad", candidate.candidate().plan().fingerprint())));
        assertEquals(base, git(repo, "rev-parse", "main"));
        report("text-conflict", Map.of("candidate", mapper.valueToTree(candidate), "executions", List.of(a, c)));
    }
    @Test void sameVersionDifferentFilesPassWorkerChecksButCombinedBehaviorRejectsProposal() throws Exception {
        for (String task : List.of("A", "B")) { String id = dispatch(task); assertEquals("SUCCEEDED", coordinator.refresh(developer, id).state()); }
        var candidate = candidate(List.of("A", "B"), "combined"); assertEquals("READY", candidate.effectiveState());
        assertTrue(candidate.candidate().plan().staticReport().conflicts().isEmpty());
        var check = integration.validate(qa, candidate.candidate().plan().id(), new IntegrationService.Validate("behavior", candidate.candidate().plan().fingerprint()));
        assertEquals("FAILED", check.state()); assertEquals(0, check.checks().getFirst().exitCode());
        assertTrue(check.checks().getLast().stderr().contains("behavior incompatible"));
        assertThrows(IllegalStateException.class, () -> integration.propose(lead, candidate.candidate().plan().id(), new IntegrationService.Propose("rejected", candidate.candidate().plan().fingerprint(), check.id())));
        assertEquals(base, git(repo, "rev-parse", "main"));
        report("behavior-conflict", Map.of("candidate", mapper.valueToTree(candidate), "validation", check));
    }
    @ParameterizedTest @ValueSource(strings = {"CONTRACT", "SCHEMA"})
    void committedSemanticArtifactInvalidatesRealExecutionAndOldCandidateAcrossRestart(String publisher) throws Exception {
        String a = dispatch("A"); coordinator.refresh(developer, a);
        var candidate = candidate(List.of("A"), "local");
        String b = dispatch("B"), changed = dispatch(publisher); coordinator.refresh(developer, changed);
        var actual = runtime.executorRun(changed); assertEquals(results.get(changed).commitSha(), actual.result().commitSha());
        String assetPath = publisher.equals("CONTRACT") ? "contracts/api.txt" : "db/schema.sql";
        assertTrue(git(Path.of(actual.request().execution().path()), "diff", "--name-only", base, actual.result().commitSha()).contains(assetPath));
        var args = envelope(publisher).put("assetVersion", 2).put("taskExecutionId", changed).put("commitSha", actual.result().commitSha());
        args.putArray("affectedTaskIds").add("A"); // B must be derived from the approved resource consumers.
        args.putArray("resourceIds").add(publisher.equals("CONTRACT") ? "API_CONTRACT" : "DB_SCHEMA");
        args.putArray("artifactPaths").add(assetPath);
        String tool = publisher.equals("CONTRACT") ? "publish_contract_change" : "publish_schema_change";
        var invalid = args.deepCopy().put("idempotencyKey", UUID.randomUUID().toString()); invalid.putArray("artifactPaths").add("client/expected.txt");
        assertTrue(rawMcp(publisher, tool, invalid).has("error"));
        var old = args.deepCopy().put("idempotencyKey", UUID.randomUUID().toString()).put("taskExecutionId", "old-execution");
        assertTrue(rawMcp(publisher, tool, old).has("error"));
        assertEquals(1, runtime.contextVersion());
        var published = mcp(publisher, tool, args);
        var sourceEvidence = evidence.read(source.planScope(), published.path("result").path("structuredContent").path("sourceEvidenceRef").asText());
        assertEquals(actual.result().commitSha(), sourceEvidence.binding().commitSha());
        assertEquals(assetPath, sourceEvidence.content().path("artifactPaths").get(0).asText());
        assertEquals("STALE", runtime.task("A").get("status").toString()); assertEquals("STALE", runtime.task("B").get("status").toString());
        assertThrows(IllegalStateException.class, () -> coordinator.refresh(developer, b));
        coordinator.close(); jdbc.execute("SHUTDOWN"); open();
        assertEquals("STALE", integration.get(lead, candidate.candidate().plan().id()).effectiveState());
        assertThrows(IllegalStateException.class, () -> coordinator.refresh(developer, b));
        assertThrows(IllegalStateException.class, () -> integration.propose(lead, candidate.candidate().plan().id(), new IntegrationService.Propose("old", candidate.candidate().plan().fingerprint(), "old-validation")));
        var architect = actor("architect", ProjectRole.ARCHITECT);
        assertThrows(IllegalStateException.class, () -> journal.operatorCommand(architect, "revalidate_task", envelope("B")));
        journal.operatorCommand(manager, "confirm_execution_stopped", envelope("B").put("taskExecutionId", b).put("evidenceRef", "Checked the completed local HTTP worker and its stopped process"));
        journal.operatorCommand(architect, "revalidate_task", envelope("B"));
        String revalidated = dispatch("B"); var current = coordinator.refresh(developer, revalidated);
        assertNotEquals(b, revalidated); assertEquals("SUCCEEDED", current.state());
        var oldResult = envelope("B").put("taskExecutionId", b); oldResult.set("result", mapper.valueToTree(results.get(b)));
        assertThrows(IllegalStateException.class, () -> journal.executorCommand(developer, "observe", oldResult));
        assertEquals(revalidated, ((McpProjectRuntime.Execution) runtime.task("B").get("execution")).executionId());
        assertEquals(2, evidence.read(source.planScope(), current.modelEvidenceRef()).binding().contextVersion());
        assertEquals(base, git(repo, "rev-parse", "main"));
        report("semantic-" + publisher, Map.of("publisherExecution", changed, "publisherCommit", actual.result().commitSha(), "sourceEvidenceRef", sourceEvidence.id(), "invalidatedExecutions", List.of(a, b), "oldCandidate", candidate.candidate().plan().id(), "revalidatedExecution", revalidated, "revalidatedCommit", current.result().commitSha(), "revalidatedModelEvidence", current.modelEvidenceRef()));
    }
    String claim(String task) { return mapper.valueToTree(journal.claimAssignedTask(developer, envelope(task))).path("task").path("execution").path("executionId").asText(); }
    String dispatch(String task) {
        String id = claim(task); coordinator.dispatch(developer, id, task, "http", runtime.contextVersion(), UUID.randomUUID().toString());
        assertNull(workerFailure.get(), () -> String.valueOf(workerFailure.get())); return id;
    }
    void release(String task) {
        String id = ((McpProjectRuntime.Execution) runtime.task(task).get("execution")).executionId();
        for (var lease : runtime.snapshot().leases().stream().filter(item -> item.taskExecutionId().equals(id)).toList())
            mcp(task, "release_paths", envelope(task).put("leaseId", lease.leaseId().toString()).put("leaseVersion", lease.version()));
    }
    JsonNode mcp(String task, String tool, ObjectNode args) {
        var result = rawMcp(task, tool, args);
        assertFalse(result.has("error"), result::toString);
        return result;
    }
    JsonNode rawMcp(String task, String tool, ObjectNode args) {
        args.put("taskToken", runtime.assignmentToken(task)); var params = mapper.createObjectNode().put("name", tool); params.set("arguments", args);
        var result = mapper.valueToTree(journal.handle(new McpRequest("2.0", mapper.getNodeFactory().numberNode(1), "tools/call", params)).getBody());
        return result;
    }
    IntegrationService.View candidate(List<String> tasks, String profile) { return integration.create(lead, new IntegrationService.Create(UUID.randomUUID().toString(), runtime.contextVersion(), tasks, profile)); }
    ObjectNode envelope(String task) { return mapper.createObjectNode().put("taskId", task).put("expectedVersion", runtime.contextVersion()).put("idempotencyKey", UUID.randomUUID().toString()); }
    static Principal actor(String name, ProjectRole role) { return new Principal(name, "legacy-blog", Set.of(role)); }
    void report(String scenario, Map<String, Object> detail) throws Exception {
        Path output = Files.createDirectories(Path.of("target", "phase9b-evidence")).resolve(scenario + ".json");
        var document = new LinkedHashMap<String, Object>(detail);
        document.put("scenario", scenario); document.put("verifiedAt", java.time.Instant.now().toString());
        document.put("planScope", source.planScope()); document.put("baseCommit", base); document.put("mainAfter", git(repo, "rev-parse", "main"));
        Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(document), StandardCharsets.UTF_8);
    }
}
