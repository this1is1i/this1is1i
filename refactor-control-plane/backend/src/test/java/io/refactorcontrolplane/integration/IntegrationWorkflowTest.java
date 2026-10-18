package io.refactorcontrolplane.integration;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.refactorcontrolplane.api.*;
import io.refactorcontrolplane.analysis.*;
import io.refactorcontrolplane.governance.*;
import io.refactorcontrolplane.mcp.*;
import io.refactorcontrolplane.persistence.AuditEventRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.*;

class IntegrationWorkflowTest {
    @TempDir Path directory;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    final LocalIdentityRegistry.Principal lead = actor("lead", ProjectRole.ENGINEERING_LEAD);
    final LocalIdentityRegistry.Principal qa = actor("qa", ProjectRole.QA_LEAD);
    EmbeddedDatabase db;
    DemoProjectService project;
    McpProjectRuntime runtime;
    ProjectMcpController journal;
    IntegrationStore store;
    IntegrationService service;
    Path repo, config;
    String main;

    @BeforeEach void setup() throws Exception {
        repo = Files.createDirectory(directory.resolve("source"));
        git(repo, "init", "-b", "main");
        write(repo, "pom.xml", "<project/>");
        write(repo, "src/Api.java", "class Api { static int value() { return 1; } }");
        write(repo, "src/Client.java", "class Client { static int value() { return Api.value(); } }");
        write(repo, "src/Schema.java", "class Schema { static int value() { return 0; } }");
        write(repo, "src/Check.java", "class Check { public static void main(String[] args) { if (Client.value()+Schema.value()!=21) throw new AssertionError(\"combined result\"); } }");
        commit(repo); main = git(repo, "rev-parse", "HEAD");
        project = new DemoProjectService(repo.toString()) {
            @Override public DemoProjectOverview overview() {
                var original = super.overview();
                var tasks = List.of("A", "B", "C").stream().map(id -> new PlanWorkPackage(id, id, "DEVELOPER",
                        Set.of(), Set.of("src"), 1, ".", List.of("configured validation"))).toList();
                return new DemoProjectOverview("legacy-blog", original.analysis(), new MigrationPlan(main, "", tasks,
                        List.of(), Map.of(), "v1", "v1", Map.of(), Set.of()), "HUMAN_ONLY", "SOURCE_CODE_STAYS_INTERNAL");
            }
        };
        db = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2)
                .addScript("schema.sql").addScript("db/migration/V3__integration_candidates.sql").build();
        var jdbc = new JdbcTemplate(db); var audit = new AuditEventRepository(jdbc);
        runtime = new McpProjectRuntime(project, audit, mapper);
        journal = new ProjectMcpController(project, runtime, audit, mapper);
        store = new IntegrationStore(jdbc, mapper, new DataSourceTransactionManager(db));
        config = directory.resolve("checks.json");
        setProfile("1", List.of(new IntegrationProfiles.Check("compile", List.of(java("javac"), "-proc:none", "src/Api.java", "src/Client.java", "src/Schema.java", "src/Check.java"), ".", 30),
                new IntegrationProfiles.Check("combined-behavior", List.of(java("java"), "-cp", "src", "Check"), ".", 30)));
        service = new IntegrationService(project, runtime, store, new IntegrationProfiles(config.toString(), mapper), "refs/heads/main");
    }
    @AfterEach void close() { if (db != null) db.shutdown(); }

    @Test void threeRealExecutionsValidateAndOnlyHumanApprovedExactCombinationCanBeProposed() throws Exception {
        happy(); var view = create("candidate"); String id = view.candidate().plan().id(), fp = view.candidate().plan().fingerprint();
        assertEquals("READY", view.effectiveState());
        assertEquals(view, create("candidate"));
        assertEquals(List.of("A", "B", "C"), view.candidate().plan().inputs().stream().map(McpProjectRuntime.IntegrationInput::taskId).toList());
        for (var input : view.candidate().plan().inputs())
            assertEquals("", git(repo, "merge-base", "--is-ancestor", input.execution().commitSha(), view.candidate().assembly().commit()));
        var validation = service.validate(qa, id, new IntegrationService.Validate("validate", fp));
        assertEquals("PASSED", validation.state(), validation::toString);
        assertEquals(2, validation.checks().size());
        assertEquals(validation, service.validate(qa, id, new IntegrationService.Validate("validate", fp)));
        var proposal = new IntegrationService.Propose("proposal", fp, validation.id());
        assertThrows(IllegalStateException.class, () -> service.propose(lead, id, proposal));
        for (String task : List.of("A", "B", "C")) accept(task);
        var result = service.propose(lead, id, proposal);
        assertFalse(result.automaticallyMergeMain()); assertEquals("HUMAN_ONLY", result.mergePolicy());
        assertEquals(result, service.propose(lead, id, proposal));
        assertThrows(IllegalStateException.class, () -> operator(lead, "A", "merge_proposal", Map.of("commitSha", sha("A"))));
        assertEquals(main, git(repo, "rev-parse", "main"));
        // Recreate services against the same durable records; no execution is rerun.
        var recovered = new IntegrationService(project, runtime, store, new IntegrationProfiles(config.toString(), mapper), "refs/heads/main");
        assertEquals("PASSED", recovered.get(qa, id).validation().state());
        assertEquals(result, recovered.propose(lead, id, proposal));
        // A new uncertain attempt supersedes an old PASS, including idempotent proposal replay.
        String attemptId = UUID.randomUUID().toString();
        store.insert(attemptId, "validation", "interrupted-later", "qa", Map.of(),
                new IntegrationService.Validation(attemptId, id, fp, result.commitSha(), "qa", "RUNNING", "now", null, List.of(), ""), List.of());
        var current = recovered.get(qa, id).candidate();
        store.save(store.get(id), new IntegrationService.Candidate(current.plan(), current.state(), current.assembly(), attemptId));
        assertThrows(IllegalStateException.class, () -> recovered.propose(lead, id, proposal));
    }

    @Test void differentFilesCanMergeButFailCompilationThenNewTaskExecutionRepairsTheCombination() throws Exception {
        submit("A", "src/Api.java", "class Api { static int newValue() { return 10; } }");
        submit("B", "src/Client.java", "class Client { static int value() { return Api.value()*2; } }");
        submit("C", "src/Schema.java", "class Schema { static int value() { return 1; } }");
        var first = create("before");
        assertEquals("READY", first.effectiveState());
        var failed = validate(first, "broken-check");
        assertEquals("FAILED", failed.state()); assertNotEquals(0, failed.checks().getFirst().exitCode());
        operator(lead, "B", "start_review", Map.of("commitSha", sha("B")));
        operator(lead, "B", "record_gate", Map.of("commitSha", sha("B"), "gate", "CODE_REVIEW", "passed", false, "evidenceRef", failed.id()));
        operator(actor("manager", ProjectRole.PROJECT_MANAGER), "B", "retry_task", Map.of());
        submit("B", "src/Client.java", "class Client { static int value() { return Api.newValue()*2; } }");
        var repaired = create("after");
        assertNotEquals(first.candidate().plan().fingerprint(), repaired.candidate().plan().fingerprint());
        assertEquals("PASSED", validate(repaired, "repaired-check").state());
        assertEquals("STALE", service.get(lead, first.candidate().plan().id()).effectiveState());
    }

    @Test void sameFileLeaseBlocksConcurrentWriterAndActualMergeConflictIsRecorded() throws Exception {
        claim("A"); claim("B");
        JsonNode lease = call("A", "reserve_paths", Map.of("ownerId", "dev", "resourcePath", "src/Api.java"));
        var denied = raw("B", "reserve_paths", Map.of("ownerId", "dev", "resourcePath", "src/Api.java"));
        assertFalse(denied.has("error"), denied::toString);
        assertTrue(denied.path("result").path("structuredContent").has("accepted"));
        assertFalse(denied.path("result").path("structuredContent").path("accepted").asBoolean());
        changeAndSubmit("A", "src/Api.java", "class Api { static int value() { return 10; } }");
        var detail = lease.path("lease");
        call("A", "release_paths", Map.of("leaseId", detail.path("leaseId").asText(), "leaseVersion", detail.path("version").asLong()));
        call("B", "reserve_paths", Map.of("ownerId", "dev", "resourcePath", "src/Api.java"));
        changeAndSubmit("B", "src/Api.java", "class Api { static int value() { return 20; } }");
        submit("C", "src/Schema.java", "class Schema { static int value() { return 1; } }");
        var conflict = create("conflict");
        assertEquals("CONFLICTED", conflict.effectiveState());
        assertEquals("B", conflict.candidate().assembly().conflictingTask());
        assertTrue(conflict.candidate().plan().staticReport().conflicts().stream().anyMatch(item -> item.kind().equals("PATH")));
        assertTrue(conflict.candidate().assembly().conflictingPaths().contains("src/Api.java"));
        assertEquals(main, git(repo, "rev-parse", "main"));
        assertThrows(IllegalStateException.class, () -> validate(conflict, "cannot-run"));
    }

    @Test void sourceTargetAndValidationConfigurationChangesInvalidatePassingEvidence() throws Exception {
        happy(); var first = create("original"); assertEquals("PASSED", validate(first, "checks").state());
        setProfile("2", List.of(new IntegrationProfiles.Check("compile", List.of(java("java"), "-version"), ".", 30)));
        assertEquals("STALE", service.get(lead, first.candidate().plan().id()).effectiveState());
        var second = create("new-profile"); assertNotEquals(first.candidate().plan().fingerprint(), second.candidate().plan().fingerprint());
        write(repo, "target.txt", "changed main"); commit(repo);
        assertEquals("STALE", service.get(lead, second.candidate().plan().id()).effectiveState());
        var third = create("new-target");
        write(tree("A"), "src/Api.java", "class Api { static int value() { return 11; } }"); commit(tree("A"));
        assertEquals("STALE", service.get(lead, third.candidate().plan().id()).effectiveState());
    }

    @Test void replayDoesNotOverwriteForeignCandidateBranchOrRerunUncertainCommands() throws Exception {
        happy(); var view = create("candidate"); var candidate = view.candidate(); String id = candidate.plan().id();
        // Simulate interruption after publication but before the durable READY result.
        store.save(store.get(id), new IntegrationService.Candidate(candidate.plan(), "ASSEMBLED", candidate.assembly(), null));
        assertEquals("READY", create("candidate").effectiveState());
        store.save(store.get(id), new IntegrationService.Candidate(candidate.plan(), "ASSEMBLED", candidate.assembly(), null));
        git(repo, "update-ref", IntegrationGit.branch(id), main, candidate.assembly().commit());
        assertThrows(IllegalStateException.class, () -> create("candidate"));
        assertEquals(main, git(repo, "rev-parse", IntegrationGit.branch(id)));
        git(repo, "update-ref", IntegrationGit.branch(id), candidate.assembly().commit(), main);
        create("candidate");
        var request = new IntegrationService.Validate("uncertain", candidate.plan().fingerprint());
        var result = service.validate(qa, id, request);
        store.save(store.get(result.id()), new IntegrationService.Validation(result.id(), id, result.fingerprint(), result.commitSha(),
                "qa", "RUNNING", result.startedAt(), null, List.of(), ""));
        assertThrows(IllegalStateException.class, () -> service.validate(qa, id, request));
        assertEquals("PASSED", service.validate(qa, id, new IntegrationService.Validate("fresh-attempt", result.fingerprint())).state());
    }

    @Test void wrongRolesSelfApprovalAndReusedKeysAreRejected() throws Exception {
        happy(); var view = create("candidate"); String id = view.candidate().plan().id(), fp = view.candidate().plan().fingerprint();
        assertThrows(SecurityException.class, () -> service.validate(lead, id, new IntegrationService.Validate("wrong-role", fp)));
        assertThrows(SecurityException.class, () -> service.validate(actor("task:A", ProjectRole.QA_LEAD), id, new IntegrationService.Validate("self", fp)));
        assertThrows(SecurityException.class, () -> service.list(new LocalIdentityRegistry.Principal("lead", "other", Set.of(ProjectRole.ENGINEERING_LEAD))));
        assertThrows(IllegalStateException.class, () -> service.create(lead, new IntegrationService.Create("candidate", 1, List.of("A"), "java")));
        assertThrows(IllegalArgumentException.class, () -> service.create(lead, new IntegrationService.Create("duplicate", 1, List.of("A", "A"), "java")));
    }

    @Test void acceptanceDoesNotAllowOmittingAnUnmergedDependency() throws Exception {
        happy(); accept("A");
        DemoProjectService original = project;
        var dependent = new DemoProjectService(repo.toString()) {
            @Override public DemoProjectOverview overview() {
                var view = original.overview(); var plan = view.plan();
                var tasks = plan.workPackages().stream().map(task -> new PlanWorkPackage(task.id(), task.title(), task.role(),
                        task.id().equals("B") ? Set.of("A") : Set.of(), task.allowedPaths(), 1, ".", task.acceptanceCommands())).toList();
                return new DemoProjectOverview(view.projectId(), view.analysis(), new MigrationPlan(main, "", tasks,
                        List.of(), Map.of(), "v1", "v1", Map.of(), Set.of()), view.mergePolicy(), view.dataBoundary());
            }
        };
        var recovered = new McpProjectRuntime(dependent, new AuditEventRepository(new JdbcTemplate(db)), mapper);
        assertEquals("ACCEPTED", recovered.task("A").get("status").toString());
        assertThrows(IllegalStateException.class, () -> recovered.integrationInputs(List.of("B")));
        assertEquals(List.of("A", "B"), recovered.integrationInputs(List.of("B", "A")).stream().map(McpProjectRuntime.IntegrationInput::taskId).toList());
        assertEquals(main, git(repo, "rev-parse", "main"));
    }

    @Test void configurationCannotUseEscapingWorkingDirectoriesAndSourceMutationFailsValidation() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new IntegrationProfiles.Check("escape", List.of("java"), "../outside", 10));
        happy();
        Path probe = directory.resolve("Mutate.java");
        Files.writeString(probe, "class Mutate {public static void main(String[] args)throws Exception {java.nio.file.Files.writeString(java.nio.file.Path.of(\"src/Api.java\"),\"modified\");}}", StandardCharsets.UTF_8);
        setProfile("mutates-source", List.of(new IntegrationProfiles.Check("mutation", List.of(java("java"), probe.toString()), ".", 30)));
        var view = create("mutation"); var result = validate(view, "check-mutation");
        assertEquals("FAILED", result.state());
        assertTrue(result.detail().contains("modified tracked source"), result::toString);
        assertEquals(main, git(repo, "rev-parse", "main"));
    }

    @Test void failedValidationRegistrationCannotLeaveAnOrphanRunningAttempt() throws Exception {
        happy(); var view = create("candidate");
        var jdbc = new JdbcTemplate(db);
        var failingStore = new IntegrationStore(jdbc, mapper, new DataSourceTransactionManager(db)) {
            @Override public Entry save(Entry before, Object payload) {
                if (payload instanceof IntegrationService.Candidate candidate && candidate.latestValidation() != null)
                    throw new IllegalStateException("Injected failure between validation insert and candidate update");
                return super.save(before, payload);
            }
        };
        var failing = new IntegrationService(project, runtime, failingStore, new IntegrationProfiles(config.toString(), mapper), "refs/heads/main");
        assertThrows(IllegalStateException.class, () -> failing.validate(qa, view.candidate().plan().id(),
                new IntegrationService.Validate("atomic-validation", view.candidate().plan().fingerprint())));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM integration_record WHERE operation_key='atomic-validation'", Integer.class));
        assertNull(service.get(lead, view.candidate().plan().id()).candidate().latestValidation());
    }

    void happy() throws Exception {
        submit("A", "src/Api.java", "class Api { static int value() { return 10; } }");
        submit("B", "src/Client.java", "class Client { static int value() { return Api.value()*2; } }");
        submit("C", "src/Schema.java", "class Schema { static int value() { return 1; } }");
    }
    @Test void staleUnknownAttemptCanBeReconciledWithoutErasingEvidenceOrPassingGates() throws Exception {
        happy(); var view=create("candidate"); var candidate=view.candidate(); String id=candidate.plan().id();
        var result=validate(view,"unknown");
        store.save(store.get(result.id()),new IntegrationService.Validation(result.id(),id,result.fingerprint(),result.commitSha(),"qa","RUNNING",result.startedAt(),null,List.of(),""));
        setProfile("changed",candidate.plan().profile().checks());
        assertFalse(service.get(qa,id).currentlyValid());
        assertThrows(IllegalStateException.class,service::requireNoUnknownLatestValidation);
        var request=new IntegrationService.Reconcile("reconcile",candidate.plan().fingerprint(),result.id(),"Process stopped; worktree inspected; no result trusted");
        assertThrows(SecurityException.class,()->service.reconcileValidation(lead,id,request));
        var reconciled=service.reconcileValidation(qa,id,request);
        assertEquals("ABANDONED",reconciled.state()); assertEquals(reconciled,service.reconcileValidation(qa,id,request));
        assertEquals("RUNNING",store.read(store.get(result.id()),IntegrationService.Validation.class).state());
        service.requireNoUnknownLatestValidation();
        assertThrows(IllegalStateException.class,()->service.propose(lead,id,new IntegrationService.Propose("cannot-pass",candidate.plan().fingerprint(),reconciled.id())));
    }
    IntegrationService.View create(String key) { return service.create(lead, new IntegrationService.Create(key, runtime.contextVersion(), List.of("C", "B", "A"), "java")); }
    IntegrationService.Validation validate(IntegrationService.View view, String key) {
        return service.validate(qa, view.candidate().plan().id(), new IntegrationService.Validate(key, view.candidate().plan().fingerprint()));
    }
    void claim(String task) { call(task, "claim_task", Map.of()); }
    void submit(String task, String path, String source) throws Exception {
        claim(task); call(task, "reserve_paths", Map.of("ownerId", "dev", "resourcePath", path)); changeAndSubmit(task, path, source);
    }
    void changeAndSubmit(String task, String path, String source) throws Exception {
        write(tree(task), path, source); commit(tree(task));
        assertTrue(call(task, "submit_changeset", Map.of("commitSha", git(tree(task), "rev-parse", "HEAD"))).path("accepted").asBoolean());
    }
    Path tree(String task) { return Path.of(((McpProjectRuntime.Execution) runtime.task(task).get("execution")).path()); }
    String sha(String task) { return ((McpProjectRuntime.Execution) runtime.task(task).get("execution")).commitSha(); }
    JsonNode call(String task, String tool, Map<String, Object> extras) {
        JsonNode response = raw(task, tool, extras); assertFalse(response.has("error"), response::toString);
        return response.path("result").path("structuredContent");
    }
    JsonNode raw(String task, String tool, Map<String, Object> extras) {
        ObjectNode arguments = mapper.valueToTree(extras);
        arguments.put("taskId", task).put("taskToken", runtime.assignmentToken(task)).put("expectedVersion", runtime.contextVersion()).put("idempotencyKey", UUID.randomUUID().toString());
        ObjectNode request = mapper.createObjectNode().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call");
        request.putObject("params").put("name", tool).set("arguments", arguments);
        return mapper.valueToTree(journal.handle(mapper.convertValue(request, McpRequest.class)).getBody());
    }
    void operator(LocalIdentityRegistry.Principal actor, String task, String action, Map<String, Object> values) {
        ObjectNode input = mapper.valueToTree(values);
        input.put("taskId", task).put("expectedVersion", runtime.contextVersion()).put("idempotencyKey", UUID.randomUUID().toString());
        journal.operatorCommand(actor, action, input);
    }
    void accept(String task) {
        operator(lead, task, "start_review", Map.of("commitSha", sha(task)));
        gate(task, QualityGate.CODE_REVIEW);
        operator(qa, task, "start_testing", Map.of("commitSha", sha(task)));
        for (var gate : QualityGate.values()) if (gate != QualityGate.CODE_REVIEW) gate(task, gate);
        operator(actor("business", ProjectRole.BUSINESS_APPROVER), task, "accept_task", Map.of("commitSha", sha(task)));
    }
    void gate(String task, QualityGate gate) {
        operator(actor("reviewer-" + gate, gate.approvingRole()), task, "record_gate", Map.of("commitSha", sha(task), "gate", gate.name(), "passed", true, "evidenceRef", "test-evidence"));
    }
    static LocalIdentityRegistry.Principal actor(String id, ProjectRole role) { return new LocalIdentityRegistry.Principal(id, "legacy-blog", Set.of(role)); }
    void setProfile(String version, List<IntegrationProfiles.Check> checks) throws Exception {
        Files.writeString(config, mapper.writeValueAsString(List.of(new IntegrationProfiles.Profile("java", version, checks))), StandardCharsets.UTF_8);
    }
    static String java(String name) { return Path.of(System.getProperty("java.home"), "bin", name + (System.getProperty("os.name").startsWith("Windows") ? ".exe" : "")).toString(); }
    static void write(Path root, String relative, String text) throws Exception {
        Path path = root.resolve(relative); Files.createDirectories(path.getParent()); Files.writeString(path, text, StandardCharsets.UTF_8);
    }
    static void commit(Path repo) { git(repo, "add", "."); git(repo, "-c", "user.name=Test", "-c", "user.email=test@localhost", "-c", "commit.gpgsign=false", "commit", "-m", "test"); }
    static String git(Path repo, String... args) {
        var command = new ArrayList<>(List.of("git", "-C", repo.toString())); command.addAll(List.of(args));
        var result = ControlledProcess.run(repo, command, Map.of(), new byte[0], 30, 1024 * 1024);
        assertTrue(result.passed(), result::err); return result.out().strip();
    }
}
