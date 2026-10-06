package io.refactorcontrolplane.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.refactorcontrolplane.api.DemoProjectService;
import io.refactorcontrolplane.persistence.AuditEventRepository;
import io.refactorcontrolplane.governance.LocalIdentityRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.*;

class GitExecutionWorkflowContractTest {
    @TempDir Path directory;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void rejectedReviewCanStartANewExecutionWithoutDiscardingThePreviousWorktree() throws Exception {
        Path repo = directory.resolve("source");
        Files.createDirectories(repo);
        git(repo, "init", "-b", "main");
        Files.writeString(repo.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        git(repo, "add", "."); commit(repo, "initial");
        var service = new DemoProjectService(repo.toString());
        EmbeddedDatabase db = new EmbeddedDatabaseBuilder().generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build();
        try {
            var audit = new AuditEventRepository(new JdbcTemplate(db));
            var runtime = new McpProjectRuntime(service, audit, mapper);
            var controller = new ProjectMcpController(service, runtime, audit, mapper);
            operator(controller, "manager", "assign_task", "assign", "{\"assignee\":\"developer\"}", 1);
            assertFalse(call(controller, runtime, "reserve_paths", "provisional-lease", """
                    {"ownerId":"developer","resourcePath":"docs/architecture"}
                    """).path("result").path("structuredContent").path("lease").isMissingNode());
            JsonNode first = call(controller, runtime, "claim_task", "first", "{}")
                    .path("result").path("structuredContent").path("task").path("execution");
            assertTrue(((List<?>) runtime.integrationStatus().get("activeLeases")).isEmpty(),
                    "READY pre-reservations must be released when switching to a real execution");
            Path worktree = Path.of(first.path("path").asText());
            Files.createDirectories(worktree.resolve("docs/architecture"));
            Files.writeString(worktree.resolve("docs/architecture/design.md"), "needs review", StandardCharsets.UTF_8);
            git(worktree, "add", "."); commit(worktree, "candidate");
            String sha = git(worktree, "rev-parse", "HEAD");
            assertFalse(call(controller, runtime, "reserve_paths", "lease", """
                    {"ownerId":"developer","resourcePath":"docs/architecture"}
                    """).has("error"));
            String submission = "{\"commitSha\":\"" + sha + "\"}";
            assertFalse(call(controller, runtime, "submit_changeset", "submit", submission).has("error"));
            operator(controller, "lead", "start_review", "start-review", submission, 1);
            operator(controller, "lead", "record_gate", "failed-review", gate(sha, "CODE_REVIEW").replace("true", "false"), 1);
            assertEquals("REJECTED", runtime.task("WP-100").get("status").toString());
            operator(controller, "manager", "retry_task", "retry", "{}", 1);
            JsonNode second = call(controller, runtime, "claim_task", "second", "{}")
                    .path("result").path("structuredContent").path("task").path("execution");
            assertNotEquals(first.path("executionId"), second.path("executionId"));
            assertEquals(sha, git(worktree, "rev-parse", "HEAD"));
            assertEquals(runtime.task("WP-100"), new McpProjectRuntime(service, audit, mapper).task("WP-100"));
        } finally { db.shutdown(); }
    }

    @Test
    void pendingClaimReusesTheDurableExecutionAndCannotHideAnUnauthorizedDiff() throws Exception {
        Path repo = directory.resolve("source");
        Files.createDirectories(repo);
        git(repo, "init", "-b", "main");
        Files.writeString(repo.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        git(repo, "add", "pom.xml"); commit(repo, "initial");
        String originalMain = git(repo, "rev-parse", "main");
        var service = new DemoProjectService(repo.toString());
        EmbeddedDatabase db = new EmbeddedDatabaseBuilder().generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build();
        try {
            var jdbc = new JdbcTemplate(db);
            var audit = new AuditEventRepository(jdbc);
            var runtime = new McpProjectRuntime(service, audit, mapper);
            var interrupted = new AuditEventRepository(jdbc) {
                @Override public boolean complete(String key, long version, String result) { return false; }
            };
            var controller = new ProjectMcpController(service, runtime, interrupted, mapper);
            assertTrue(call(controller, runtime, "claim_task", "interrupted-claim", "{}").has("error"));
            assertEquals("READY", runtime.task("WP-100").get("status").toString());
            assertEquals(true, runtime.integrationStatus().get("reconciliationRequired"));
            String branches = git(repo, "for-each-ref", "--format=%(refname)", "refs/heads/");
            assertEquals(2, branches.lines().count());
            var recovered = new McpProjectRuntime(service, audit, mapper);
            assertEquals("COMPLETED", audit.findByIdempotencyKey("interrupted-claim").orElseThrow().status());
            assertEquals(branches, git(repo, "for-each-ref", "--format=%(refname)", "refs/heads/"));
            assertEquals(recovered.task("WP-100"), new McpProjectRuntime(service, audit, mapper).task("WP-100"));
            var active = new ProjectMcpController(service, recovered, audit, mapper);
            JsonNode execution = mapper.valueToTree(recovered.task("WP-100").get("execution"));
            Path worktree = Path.of(execution.path("path").asText());
            Files.createDirectories(worktree.resolve("docs/architecture"));
            Files.writeString(worktree.resolve("docs/architecture/allowed.md"), "allowed", StandardCharsets.UTF_8);
            Files.writeString(worktree.resolve("pom.xml"), "<project>unauthorized</project>", StandardCharsets.UTF_8);
            git(worktree, "add", "."); commit(worktree, "hidden unauthorized change");
            assertFalse(call(active, recovered, "reserve_paths", "reserve", """
                    {"ownerId":"developer","resourcePath":"docs/architecture"}
                    """).has("error"));
            JsonNode result = call(active, recovered, "submit_changeset", "hidden-change",
                    "{\"commitSha\":\"" + git(worktree, "rev-parse", "HEAD")
                            + "\",\"changedPaths\":[\"docs/architecture/allowed.md\"]}");
            assertTrue(result.has("error"), result::toString);
            assertEquals("CLAIMED", recovered.task("WP-100").get("status").toString());
            Files.writeString(worktree.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
            Files.createDirectories(worktree.resolve(" docs/architecture"));
            Files.writeString(worktree.resolve(" docs/architecture/escape.md"), "escape", StandardCharsets.UTF_8);
            git(worktree, "add", "."); commit(worktree, "ambiguous path");
            JsonNode ambiguous = call(active, recovered, "submit_changeset", "ambiguous-path",
                    "{\"commitSha\":\"" + git(worktree, "rev-parse", "HEAD") + "\"}");
            assertTrue(ambiguous.has("error"), ambiguous::toString);
            assertEquals(originalMain, git(repo, "rev-parse", "main"));
        } finally { db.shutdown(); }
    }

    @Test
    void claimCreatesAnIsolatedExecutionAndSubmissionUsesTheRealCommitAndLease() throws Exception {
        Path repo = directory.resolve("source");
        Files.createDirectories(repo);
        git(repo, "init", "-b", "main");
        Files.writeString(repo.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        git(repo, "add", "pom.xml");
        commit(repo, "initial");
        String mainBefore = git(repo, "rev-parse", "main");
        var service = new DemoProjectService(repo.toString());
        EmbeddedDatabase db = new EmbeddedDatabaseBuilder().generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build();
        try {
            var audit = new AuditEventRepository(new JdbcTemplate(db));
            var runtime = new McpProjectRuntime(service, audit, mapper);
            var controller = new ProjectMcpController(service, runtime, audit, mapper);
            operator(controller, "manager", "assign_task", "assign", "{\"assignee\":\"developer\"}", 1);
            JsonNode claimed = call(controller, runtime, "claim_task", "claim", "{}");
            assertFalse(claimed.has("error"), claimed::toString);
            JsonNode execution = claimed.path("result").path("structuredContent").path("task").path("execution");
            assertFalse(execution.isMissingNode(), "claim must create a real isolated execution");
            Path worktree = Path.of(execution.path("path").asText());
            assertTrue(Files.exists(worktree.resolve(".git")));
            assertEquals(mainBefore, git(repo, "rev-parse", "main"));
            assertEquals(mapper.readTree(claimed.toString()),
                    mapper.readTree(call(controller, runtime, "claim_task", "claim", "{}").toString()));

            assertTrue(call(controller, runtime, "submit_changeset", "missing-commit", "{}").has("error"));
            Files.createDirectories(worktree.resolve("docs/architecture"));
            Files.writeString(worktree.resolve("docs/architecture/decision.md"), "approved plan", StandardCharsets.UTF_8);
            git(worktree, "add", "docs/architecture/decision.md");
            commit(worktree, "architecture");
            String sha = git(worktree, "rev-parse", "HEAD");
            String submission = "{\"commitSha\":\"" + sha + "\"}";
            assertTrue(call(controller, runtime, "submit_changeset", "no-lease", submission).has("error"));
            JsonNode lease = call(controller, runtime, "reserve_paths", "reserve", """
                    {"ownerId":"architect","resourcePath":"docs/architecture"}
                    """);
            assertFalse(lease.has("error"), lease::toString);
            JsonNode submitted = call(controller, runtime, "submit_changeset", "submit", submission);
            assertTrue(submitted.path("result").path("structuredContent").path("accepted").asBoolean(), submitted::toString);
            assertEquals("SUBMITTED", runtime.task("WP-100").get("status").toString());

            assertThrows(SecurityException.class, () -> operator(controller, "developer", "start_review",
                    "self-review", submission, 1));
            assertThrows(IllegalStateException.class, () -> operator(controller, "lead", "start_review",
                    "wrong-commit", "{\"commitSha\":\"" + mainBefore + "\"}", 1));
            operator(controller, "lead", "start_review", "review-start", submission, 1);
            operator(controller, "lead", "record_gate", "review-pass", gate(sha, "CODE_REVIEW"), 1);
            operator(controller, "qa", "start_testing", "testing-start", submission, 1);
            operator(controller, "qa", "record_gate", "unit-pass", gate(sha, "UNIT_TEST"), 1);
            operator(controller, "qa", "record_gate", "integration-pass", gate(sha, "INTEGRATION_TEST"), 1);
            operator(controller, "business", "record_gate", "business-pass", gate(sha, "BUSINESS_ACCEPTANCE"), 1);
            operator(controller, "business", "accept_task", "accept", submission, 1);
            assertTrue(call(controller, runtime, "reserve_paths", "lease-after-acceptance", """
                    {"ownerId":"developer","resourcePath":"docs/contracts"}
                    """).has("error"));
            JsonNode proposal = operator(controller, "lead", "merge_proposal", "proposal", submission, 1);
            assertTrue(proposal.path("humanApprovalRequired").asBoolean());
            assertFalse(proposal.path("automaticallyMergeMain").asBoolean(true));
            assertEquals(sha, proposal.path("commitSha").asText());
            assertEquals(List.of("WP-110"), runtime.readyTasks().stream().map(task -> task.get("id")).toList());

            var recovered = new McpProjectRuntime(service, audit, mapper);
            assertEquals(runtime.task("WP-100"), recovered.task("WP-100"));
            assertEquals(mainBefore, git(repo, "rev-parse", "main"));

            String oldToken = runtime.assignmentToken("WP-100");
            call(controller, runtime, "publish_contract_change", "contract-v2", "{\"affectedTaskIds\":[\"WP-100\"]}");
            assertThrows(RuntimeException.class, () -> operator(controller, "lead", "merge_proposal", "stale-proposal", submission, 2));
            operator(controller, "architect", "revalidate_task", "revalidate", "{}", 2);
            assertNotEquals(oldToken, runtime.assignmentToken("WP-100"));
            JsonNode nextClaim = call(controller, runtime, "claim_task", "claim-second-execution", "{}");
            assertFalse(nextClaim.has("error"), nextClaim::toString);
            JsonNode nextExecution = nextClaim.path("result").path("structuredContent").path("task").path("execution");
            assertNotEquals(execution.path("executionId").asText(), nextExecution.path("executionId").asText());
            assertNotEquals(execution.path("branch").asText(), nextExecution.path("branch").asText());
            assertEquals(2L, runtime.task("WP-100").get("contextVersion"));
            assertEquals(mainBefore, git(repo, "rev-parse", "main"));
        } finally { db.shutdown(); }
    }

    JsonNode operator(ProjectMcpController controller, String subject, String action, String key, String extra, long version)
            throws Exception {
        var identities = new LocalIdentityRegistry("classpath:review-identities.json", mapper);
        var principal = identities.authenticate("Bearer " + subject + "-test-token");
        ObjectNode command = (ObjectNode) mapper.readTree(extra);
        command.put("taskId", "WP-100").put("action", action).put("idempotencyKey", key).put("expectedVersion", version);
        return mapper.valueToTree(controller.operatorCommand(principal, action, command));
    }

    static String gate(String sha, String gate) {
        return "{\"commitSha\":\"" + sha + "\",\"gate\":\"" + gate
                + "\",\"passed\":true,\"evidenceRef\":\"test-evidence/" + gate + "\"}";
    }

    JsonNode call(ProjectMcpController controller, McpProjectRuntime runtime,
            String name, String key, String extra) throws Exception {
        ObjectNode arguments = (ObjectNode) mapper.readTree(extra);
        arguments.put("taskId", "WP-100").put("taskToken", runtime.assignmentToken("WP-100"))
                .put("expectedVersion", runtime.contextVersion()).put("idempotencyKey", key);
        ObjectNode params = mapper.createObjectNode().put("name", name);
        params.set("arguments", arguments);
        JsonNode response = mapper.valueToTree(controller.handle(new McpRequest(
                "2.0", mapper.getNodeFactory().numberNode(1), "tools/call", params)).getBody());
        if (response.has("result")) McpToolSchemas.validate(mapper.valueToTree(McpToolSchemas.output(name)),
                response.path("result").path("structuredContent"), name);
        return response;
    }

    static void commit(Path repo, String message) throws Exception {
        git(repo, "-c", "user.name=Contract Test", "-c", "user.email=test@example.invalid", "commit", "-m", message);
    }

    static String git(Path repo, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", repo.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertEquals(0, process.waitFor(), output);
        return output;
    }
}
