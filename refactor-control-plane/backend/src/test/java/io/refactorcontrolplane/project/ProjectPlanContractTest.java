package io.refactorcontrolplane.project;

import static org.junit.jupiter.api.Assertions.*;
import static io.refactorcontrolplane.api.ConfiguredProjectApiContractTest.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.refactorcontrolplane.api.DemoProjectService;
import io.refactorcontrolplane.governance.LocalIdentityRegistry.Principal;
import io.refactorcontrolplane.governance.ProjectRole;
import io.refactorcontrolplane.mcp.*;
import io.refactorcontrolplane.persistence.AuditEventRepository;
import io.refactorcontrolplane.integration.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.*;

class ProjectPlanContractTest {
    @TempDir Path directory;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final Principal architect = actor("architect", ProjectRole.ARCHITECT);
    final Principal manager = actor("manager", ProjectRole.PROJECT_MANAGER);
    EmbeddedDatabase db;
    JdbcTemplate jdbc;
    Path repo, file;
    String base;
    ObjectNode input;

    @BeforeEach void setup() {
        db = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2)
                .addScript("schema.sql").addScript("db/migration/V3__integration_candidates.sql").build();
        jdbc = new JdbcTemplate(db);
    }
    @AfterEach void close() { db.shutdown(); }

    @ParameterizedTest @ValueSource(strings={"services/catalog", "apps/web/client"})
    void sameRuntimeClaimsAndRecoversTwoRepositoryLayouts(String layout) throws Exception {
        fixture(layout); var project = load(); approve(project);
        var audit = new AuditEventRepository(jdbc);
        var runtime = new McpProjectRuntime(project, audit, mapper);
        var journal = new ProjectMcpController(project, runtime, audit, mapper);
        var command = mapper.createObjectNode().put("taskId", "CAT-1").put("assignee", "developer")
                .put("expectedVersion", 1).put("idempotencyKey", "assign");
        journal.operatorCommand(manager, "assign_task", command);
        command.remove("assignee"); command.put("idempotencyKey", "claim");
        var claimed = mapper.valueToTree(journal.claimAssignedTask(actor("developer", ProjectRole.DEVELOPER), command));
        var restart = load(); assertTrue(restart.approved());
        var recovered = new McpProjectRuntime(restart, audit, mapper);
        assertEquals(claimed.path("task").path("execution"), mapper.valueToTree(recovered.task("CAT-1").get("execution")));
        assertEquals(layout, recovered.task("CAT-1").get("workingDirectory"));
        assertEquals(base, git(repo, "rev-parse", "main"));
        assertThrows(IllegalStateException.class, () -> new McpProjectRuntime(new DemoProjectService(repo.toString()), audit, mapper));
    }

    @ParameterizedTest @ValueSource(strings={"cycle", "unknown-dependency", "unknown-resource", "resource-path", "role", "missing-directory", "traversal", "git-path", "commands", "profile", "duplicate-task", "target-ref"})
    void invalidPlansNeverReachPersistence(String kind) throws Exception {
        fixture("services/catalog");
        var task = (ObjectNode) input.path("plan").path("workPackages").get(0);
        switch (kind) {
            case "cycle" -> task.putArray("dependencies").add("CAT-1");
            case "unknown-dependency" -> task.putArray("dependencies").add("missing");
            case "unknown-resource" -> ((ObjectNode) input.path("taskResources")).putArray("CAT-1").add("missing");
            case "resource-path" -> ((ObjectNode) input.path("resources").path("catalog")).put("locator", "other");
            case "role" -> task.put("role", "SUPERUSER");
            case "missing-directory" -> {
                task.put("workingDirectory", "untracked");
                ((ObjectNode) input.path("validationProfiles").get(0).path("checks").get(0)).put("workingDirectory", "untracked");
                Files.createDirectories(repo.resolve("untracked"));
            }
            case "traversal" -> task.putArray("allowedPaths").add("../outside");
            case "git-path" -> task.putArray("allowedPaths").add(".git/config");
            case "commands" -> task.putArray("acceptanceCommands").add("unapproved command");
            case "profile" -> ((ObjectNode) input.path("taskProfiles")).put("CAT-1", "missing");
            case "duplicate-task" -> ((ArrayNode) input.path("plan").path("workPackages")).add(task.deepCopy());
            case "target-ref" -> input.put("targetRef", "refs/heads/missing");
        }
        assertThrows(IllegalArgumentException.class, this::load, kind);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM project_plan", Integer.class));
    }

    @Test void approvedSnapshotCannotBeReplacedByEditedConfigurationOrDatabase() throws Exception {
        fixture("src"); var project = load(); approve(project);
        String before = jdbc.queryForObject("SELECT definition_json FROM project_plan", String.class);
        ((ObjectNode) input.path("plan")).put("apiContract", "changed");
        assertThrows(IllegalStateException.class, this::load);
        assertEquals(before, jdbc.queryForObject("SELECT definition_json FROM project_plan", String.class));
        jdbc.update("UPDATE project_plan SET definition_json='{}'");
        assertThrows(IllegalStateException.class, project::requireExecutable);
    }

    @Test void baselineChangeBlocksWritesBeforeJournalAndRestart() throws Exception {
        fixture("src"); var project = load(); approve(project);
        var audit = new AuditEventRepository(jdbc); var runtime = new McpProjectRuntime(project, audit, mapper);
        var journal = new ProjectMcpController(project, runtime, audit, mapper);
        Files.writeString(repo.resolve("src/pom.xml"), "<project>changed</project>", StandardCharsets.UTF_8);
        git(repo, "add", "."); commit(repo);
        var request = mapper.createObjectNode().put("taskId", "CAT-1").put("assignee", "developer").put("expectedVersion", 1).put("idempotencyKey", "stale");
        assertThrows(IllegalStateException.class, () -> journal.operatorCommand(manager, "assign_task", request));
        assertTrue(audit.findAll().isEmpty()); assertThrows(IllegalStateException.class, this::load);
    }

    @Test void approvalsBindRoleSubjectFingerprintAndOrder() throws Exception {
        fixture("src"); var project = load(); String fp = (String) project.state(manager).get("fingerprint");
        assertThrows(IllegalStateException.class, () -> project.decide(manager, fp, "EXECUTION"));
        assertThrows(SecurityException.class, () -> project.decide(manager, fp, "ARCHITECTURE"));
        assertThrows(IllegalStateException.class, () -> project.decide(architect, "outdated", "ARCHITECTURE"));
        assertThrows(SecurityException.class, () -> project.state(new Principal("architect", "other", Set.of(ProjectRole.ARCHITECT))));
        assertEquals(project.decide(architect, fp, "ARCHITECTURE"), project.decide(architect, fp, "ARCHITECTURE"));
        assertThrows(SecurityException.class, () -> project.decide(actor("architect", ProjectRole.PROJECT_MANAGER), fp, "EXECUTION"));
        project.decide(manager, fp, "EXECUTION"); assertTrue(project.approved());
    }

    @Test void oldUnboundJournalIsNeverReinterpretedAsConfiguredProject() throws Exception {
        fixture("src"); new AuditEventRepository(jdbc).record("old", "WP-100", "claim_task", "{}", 1);
        assertThrows(IllegalStateException.class, this::load);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM project_plan", Integer.class));
    }

    @ParameterizedTest @ValueSource(strings={"baseline", "profile"})
    void integrationCannotBypassApprovedBaselineOrTaskChecks(String change) throws Exception {
        fixture("src");
        var other = ((ObjectNode) input.path("validationProfiles").get(0)).deepCopy(); other.put("id", "unrelated");
        ((ObjectNode) other.path("checks").get(0)).putArray("argv").add("git").add("status").add("--short");
        ((ArrayNode) input.path("validationProfiles")).add(other);
        var project = load(); approve(project);
        var runtime = new McpProjectRuntime(project, new AuditEventRepository(jdbc), mapper);
        var journal = new ProjectMcpController(project, runtime, new AuditEventRepository(jdbc), mapper);
        var request = mapper.createObjectNode().put("taskId", "CAT-1").put("assignee", "developer").put("expectedVersion", 1).put("idempotencyKey", "assign");
        journal.operatorCommand(manager, "assign_task", request);
        request.remove("assignee"); request.put("idempotencyKey", "claim");
        var claimed = mapper.valueToTree(journal.claimAssignedTask(actor("developer", ProjectRole.DEVELOPER), request));
        Path worktree = Path.of(claimed.path("task").path("execution").path("path").asText());
        Files.writeString(worktree.resolve("src/change.txt"), "task output", StandardCharsets.UTF_8);
        git(worktree, "add", "."); commit(worktree);
        call(journal, runtime, "reserve_paths", mapper.createObjectNode().put("ownerId", "developer").put("resourcePath", "src"));
        call(journal, runtime, "submit_changeset", mapper.createObjectNode().put("commitSha", git(worktree, "rev-parse", "HEAD")));
        var profiles = new IntegrationProfiles("", mapper, project);
        var store = new IntegrationStore(jdbc, mapper, new org.springframework.jdbc.datasource.DataSourceTransactionManager(db));
        var integration = new IntegrationService(project, runtime, store, profiles, "refs/heads/main");
        if (change.equals("baseline")) {
            Files.writeString(repo.resolve("extra.txt"), "unreviewed target", StandardCharsets.UTF_8); git(repo, "add", "."); commit(repo);
        }
        String profile = change.equals("profile") ? "unrelated" : "catalog-check";
        assertThrows(IllegalStateException.class, () -> integration.create(actor("lead", ProjectRole.ENGINEERING_LEAD),
                new IntegrationService.Create("candidate", 1, List.of("CAT-1"), profile)));
        assertTrue(store.candidates().isEmpty());
    }

    void call(ProjectMcpController journal, McpProjectRuntime runtime, String name, ObjectNode arguments) {
        arguments.put("taskId", "CAT-1").put("taskToken", runtime.assignmentToken("CAT-1"))
                .put("expectedVersion", 1).put("idempotencyKey", name);
        var params = mapper.createObjectNode().put("name", name); params.set("arguments", arguments);
        var response = mapper.valueToTree(journal.handle(new McpRequest("2.0", mapper.getNodeFactory().numberNode(1), "tools/call", params)).getBody());
        assertFalse(response.has("error"), response::toString);
    }

    @Test void newWorkingDirectoryCannotAssumePrerequisiteOutputsExistInItsBase() throws Exception {
        fixture("src");
        var task = (ObjectNode) input.path("plan").path("workPackages").get(0);
        var creator = task.deepCopy(); creator.put("id", "CREATE").put("workingDirectory", "src");
        creator.putArray("allowedPaths").add("modern");
        task.put("workingDirectory", "modern"); task.putArray("allowedPaths").add("modern"); task.putArray("dependencies").add("CREATE");
        ((ArrayNode) input.path("plan").path("workPackages")).add(creator);
        ((ObjectNode) input.path("directoryCreators")).put("modern", "CREATE");
        ((ObjectNode) input.path("resources").path("catalog")).put("locator", "modern");
        ((ObjectNode) input.path("taskResources")).putArray("CREATE").add("catalog");
        var profile = (ObjectNode) input.path("validationProfiles").get(0);
        var createProfile = profile.deepCopy(); createProfile.put("id", "create-check");
        ((ObjectNode) profile.path("checks").get(0)).put("workingDirectory", "modern");
        ((ArrayNode) input.path("validationProfiles")).add(createProfile);
        ((ObjectNode) input.path("taskProfiles")).put("CREATE", "create-check");
        assertThrows(IllegalArgumentException.class, this::load);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM project_plan", Integer.class));
    }

    void fixture(String layout) throws Exception {
        repo = Files.createDirectories(directory.resolve("source")); git(repo, "init", "-b", "main");
        Files.createDirectories(repo.resolve(layout)); Files.writeString(repo.resolve(layout).resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        git(repo, "add", "."); commit(repo); base = git(repo, "rev-parse", "HEAD");
        input = configuration(repo, base, layout); file = directory.resolve("project.json");
    }

    @Test void acceptedScaffoldBecomesReviewedBaselineWithoutMovingMain() throws Exception {
        fixture("src");
        ((ObjectNode)input.path("plan").path("workPackages").get(0)).putArray("allowedPaths").add("modern").add("src");
        var source=load(); approve(source);
        var store=new IntegrationStore(jdbc,mapper,new org.springframework.jdbc.datasource.DataSourceTransactionManager(db));
        var profiles=new IntegrationProfiles("",mapper,source);
        var policy=new io.refactorcontrolplane.integration.GateEvidencePolicy(new io.refactorcontrolplane.governance.EvidenceStore(jdbc,mapper),store,profiles,source,mapper,"refs/heads/main");
        var audit=new AuditEventRepository(jdbc); var runtime=new McpProjectRuntime(source,audit,mapper,policy);
        var journal=new ProjectMcpController(source,runtime,audit,mapper);
        var integration=new IntegrationService(source,runtime,store,profiles,"refs/heads/main");
        var revisions=new ProjectRevisions(source,jdbc,runtime,integration);
        call(journal,runtime,"claim_task",mapper.createObjectNode());
        var changed=input.deepCopy(); ((ObjectNode)changed.path("plan")).put("apiContract","v2");
        var pending=revisions.stage(architect,source.planScope(),changed);
        revisions.decide(architect,pending.fingerprint(),pending.fingerprint(),"ARCHITECTURE");
        revisions.decide(manager,pending.fingerprint(),pending.fingerprint(),"EXECUTION");
        assertThrows(IllegalStateException.class,()->revisions.activate(manager,pending.fingerprint(),source.planScope()));
        Path tree=Path.of(((McpProjectRuntime.Execution)runtime.task("CAT-1").get("execution")).path());
        Files.createDirectories(tree.resolve("modern")); Files.writeString(tree.resolve("modern/pom.xml"),"<project/>",StandardCharsets.UTF_8);
        git(tree,"add","."); commit(tree); String sha=git(tree,"rev-parse","HEAD");
        call(journal,runtime,"reserve_paths",mapper.createObjectNode().put("ownerId","developer").put("resourcePath","modern"));
        call(journal,runtime,"submit_changeset",mapper.createObjectNode().put("commitSha",sha));
        var candidate=integration.create(actor("lead",ProjectRole.ENGINEERING_LEAD),new IntegrationService.Create("candidate",1,List.of("CAT-1"),"catalog-check")).candidate();
        String combined=mapper.valueToTree(candidate).path("assembly").path("commit").asText();
        var next=configuration(repo,combined,"modern"); next.put("targetCommit",base).put("baselineCandidateId",candidate.plan().id());
        assertThrows(IllegalStateException.class,()->revisions.stage(architect,source.planScope(),next));
        var firstValidation=integration.validate(actor("qa",ProjectRole.QA_LEAD),candidate.plan().id(),new IntegrationService.Validate("validation",candidate.plan().fingerprint()));
        // Simulate the durable RUNNING record left by a process that died before saving its result.
        store.save(store.get(firstValidation.id()),new IntegrationService.Validation(firstValidation.id(),candidate.plan().id(),firstValidation.fingerprint(),firstValidation.commitSha(),"qa","RUNNING",firstValidation.startedAt(),null,List.of(),""));
        assertThrows(IllegalStateException.class,integration::requireNoUnknownLatestValidation);
        var freshValidation=integration.validate(actor("qa",ProjectRole.QA_LEAD),candidate.plan().id(),new IntegrationService.Validate("fresh-validation",candidate.plan().fingerprint()));
        integration.requireNoUnknownLatestValidation();
        assertThrows(IllegalStateException.class,()->revisions.stage(architect,source.planScope(),next));
        op(journal,runtime,actor("lead",ProjectRole.ENGINEERING_LEAD),"start_review",Map.of("commitSha",sha));
        op(journal,runtime,actor("lead",ProjectRole.ENGINEERING_LEAD),"record_gate",Map.of("commitSha",sha,"gate","CODE_REVIEW","passed",true,"statement","review"));
        op(journal,runtime,actor("qa",ProjectRole.QA_LEAD),"start_testing",Map.of("commitSha",sha));
        for (var gate:io.refactorcontrolplane.governance.QualityGate.values()) if (!gate.name().equals("CODE_REVIEW"))
            op(journal,runtime,actor("reviewer-"+gate,gate.approvingRole()),"record_gate",gate==io.refactorcontrolplane.governance.QualityGate.BUSINESS_ACCEPTANCE
                    ? Map.of("commitSha",sha,"gate",gate.name(),"passed",true,"statement","Business behavior checked")
                    : Map.of("commitSha",sha,"gate",gate.name(),"passed",true,"candidateId",candidate.plan().id(),"validationId",freshValidation.id()));
        op(journal,runtime,actor("business",ProjectRole.BUSINESS_APPROVER),"accept_task",Map.of("commitSha",sha));
        String blob=git(repo,"rev-parse",combined+":modern/pom.xml");
        next.putArray("architectureDecisions").addObject().put("id","ADR-1").put("statement","Use isolated modern application")
                .set("evidence",mapper.valueToTree(List.of(Map.of("path","modern/pom.xml","blobId",blob))));
        ((ObjectNode)next.path("architectureDecisions").get(0)).putArray("taskIds").add("CAT-1");
        var forged=next.deepCopy(); ((ObjectNode)forged.path("architectureDecisions").get(0).path("evidence").get(0)).put("blobId",base);
        assertThrows(IllegalArgumentException.class,()->revisions.stage(architect,source.planScope(),forged));
        var revision=revisions.stage(architect,source.planScope(),next);
        revisions.decide(architect,revision.fingerprint(),revision.fingerprint(),"ARCHITECTURE");
        revisions.decide(manager,revision.fingerprint(),revision.fingerprint(),"EXECUTION");
        assertEquals(true,revisions.activate(manager,revision.fingerprint(),source.planScope()).get("restartRequired"));
        var restarted=load(); var newAudit=new AuditEventRepository(jdbc); var newRuntime=new McpProjectRuntime(restarted,newAudit,mapper);
        var newJournal=new ProjectMcpController(restarted,newRuntime,newAudit,mapper);
        var args=mapper.createObjectNode().put("taskId","CAT-1").put("taskToken",newRuntime.assignmentToken("CAT-1"))
                .put("expectedVersion",1).put("idempotencyKey","new-claim");
        var params=mapper.createObjectNode().put("name","claim_task"); params.set("arguments",args);
        var result=mapper.valueToTree(newJournal.handle(new McpRequest("2.0",mapper.getNodeFactory().numberNode(1),"tools/call",params)).getBody());
        assertFalse(result.has("error"),result::toString);
        Path downstream=Path.of(((McpProjectRuntime.Execution)newRuntime.task("CAT-1").get("execution")).path());
        assertEquals("<project/>",Files.readString(downstream.resolve("modern/pom.xml"),StandardCharsets.UTF_8));
        assertEquals(combined,git(downstream,"rev-parse","HEAD"));
        assertEquals(base,git(repo,"rev-parse","main"));
        var newIntegration=new IntegrationService(restarted,newRuntime,store,new IntegrationProfiles("",mapper,restarted),"refs/heads/main");
        assertFalse(store.enrolled("CAT-1"));
        assertThrows(IllegalStateException.class,()->store.byKey("candidate"));
        assertFalse(newIntegration.get(manager,candidate.plan().id()).currentlyValid());
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE plan_scope=?",Integer.class,source.planScope())>0);
    }
    void op(ProjectMcpController journal,McpProjectRuntime runtime,Principal actor,String action,Map<String,Object> values) {
        ObjectNode arguments=mapper.valueToTree(values); arguments.put("taskId","CAT-1").put("expectedVersion",runtime.contextVersion()).put("idempotencyKey",UUID.randomUUID().toString());
        if (action.equals("record_gate")) arguments.put("taskExecutionId",((McpProjectRuntime.Execution)runtime.task("CAT-1").get("execution")).executionId());
        journal.operatorCommand(actor,action,arguments);
    }
    ConfiguredProjectService load() throws Exception {
        Files.writeString(file, input.toString(), StandardCharsets.UTF_8);
        return new ConfiguredProjectService(file.toString(), jdbc, mapper);
    }
    void approve(ConfiguredProjectService project) {
        String fp = (String) project.state(manager).get("fingerprint");
        project.decide(architect, fp, "ARCHITECTURE"); project.decide(manager, fp, "EXECUTION");
    }
    static Principal actor(String id, ProjectRole role) { return new Principal(id, "legacy-blog", Set.of(role)); }
}
