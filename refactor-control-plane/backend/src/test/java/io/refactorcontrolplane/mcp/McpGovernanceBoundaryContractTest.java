package io.refactorcontrolplane.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.refactorcontrolplane.api.DemoProjectService;
import io.refactorcontrolplane.persistence.AuditEventRepository;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

class McpGovernanceBoundaryContractTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final EmbeddedDatabase database = new EmbeddedDatabaseBuilder().generateUniqueName(true)
            .setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build();
    private final AuditEventRepository audit = new AuditEventRepository(new JdbcTemplate(database));
    private final DemoProjectService service = new DemoProjectService("../samples/workspaces/springmvc-blog");
    private final McpProjectRuntime runtime = new McpProjectRuntime(service, audit, mapper);
    private final ProjectMcpController controller = new ProjectMcpController(service, runtime, audit, mapper);

    @AfterEach
    void close() { database.shutdown(); }

    @Test
    void taskTokenCannotAdvanceItselfThroughApprovalStates() throws Exception {
        boolean rejected = false;
        for (String status : List.of("CLAIMED", "RUNNING", "SUBMITTED", "CODE_REVIEW", "TESTING", "ACCEPTED")) {
            JsonNode result = call("WP-130", "report_change_event", "status-" + status,
                    "{\"type\":\"TaskStatusChanged\",\"targetStatus\":\"" + status + "\"}");
            if (result.has("error")) { rejected = true; break; }
        }
        assertTrue(rejected, "an executor must not approve itself by reporting status events");
        assertNotEquals("ACCEPTED", runtime.task("WP-130").get("status").toString());
    }

    @Test
    void taskTokenCannotSelectAnApprovingRole() throws Exception {
        JsonNode result = call("WP-130", "report_change_event", "forged-role", """
                {"type":"QualityGateReported","gate":"CODE_REVIEW","role":"ENGINEERING_LEAD","passed":true}
                """);
        assertEquals(-32002, result.path("error").path("code").asInt(),
                "authorization must reject a task credential before checking gate state");
    }

    @Test
    void taskTokenCannotReleaseAnotherExecutionsLease() throws Exception {
        JsonNode reserved = call("WP-100", "reserve_paths", "lease", """
                {"ownerId":"developer","resourcePath":"docs/contracts/articles.openapi.yaml"}
                """);
        JsonNode lease = reserved.path("result").path("structuredContent").path("lease");
        assertFalse(lease.isMissingNode());
        JsonNode released = call("WP-130", "release_paths", "foreign-release",
                "{\"leaseId\":\"" + lease.path("leaseId").asText()
                        + "\",\"taskExecutionId\":\"exec-WP-100\",\"leaseVersion\":1}");
        assertTrue(released.has("error"));
        assertEquals(1, ((List<?>) runtime.integrationStatus().get("activeLeases")).size());
    }

    @Test
    void readyTasksAndClaimsRespectPredecessors() throws Exception {
        assertEquals(List.of("WP-100"), runtime.readyTasks().stream().map(task -> task.get("id")).toList());
        JsonNode result = call("WP-160", "claim_task", "premature-acceptance", "{}");
        assertTrue(result.has("error"));
    }

    @Test
    void rejectedSemanticPublicationReplaysToTheSameState() throws Exception {
        JsonNode result = call("WP-100", "publish_contract_change", "invalid-publication", """
                {"affectedTaskIds":["WP-130","missing-task"]}
                """);
        assertTrue(result.has("error"));
        McpProjectRuntime recovered = new McpProjectRuntime(service, audit, mapper);
        assertEquals(runtime.contextVersion(), recovered.contextVersion());
        assertEquals(runtime.task("WP-130"), recovered.task("WP-130"));
    }

    @Test
    void journalCompletionFailureDoesNotPublishAnUncommittedProjection() throws Exception {
        AuditEventRepository failing = new AuditEventRepository(new JdbcTemplate(database)) {
            @Override public boolean complete(String key, long version, String result) { return false; }
        };
        var failingController = new ProjectMcpController(service, runtime, failing, mapper);
        ObjectNode arguments = mapper.createObjectNode().put("taskId", "WP-100")
                .put("taskToken", runtime.assignmentToken("WP-100"))
                .put("idempotencyKey", "completion-failure").put("expectedVersion", 1);
        arguments.putArray("affectedTaskIds").add("WP-130");
        ObjectNode params = mapper.createObjectNode().put("name", "publish_contract_change");
        params.set("arguments", arguments);
        JsonNode response = mapper.valueToTree(failingController.handle(new McpRequest(
                "2.0", mapper.getNodeFactory().numberNode(1), "tools/call", params)).getBody());

        assertTrue(response.has("error"));
        assertEquals(1, runtime.contextVersion());
        assertEquals("READY", runtime.task("WP-130").get("status").toString());
        assertEquals("PENDING", audit.findByIdempotencyKey("completion-failure").orElseThrow().status());

        McpProjectRuntime recovered = new McpProjectRuntime(service, audit, mapper);
        assertEquals(2, recovered.contextVersion());
        assertEquals("STALE", recovered.task("WP-130").get("status").toString());
        assertEquals("COMPLETED", audit.findByIdempotencyKey("completion-failure").orElseThrow().status());
    }

    @Test
    void managerCanRetryABlockedTaskWithAFreshCredential() throws Exception {
        String previous = runtime.assignmentToken("WP-100");
        assertFalse(call("WP-100", "report_blocker", "blocked", "{}").has("error"));
        var principal = new io.refactorcontrolplane.governance.LocalIdentityRegistry("classpath:review-identities.json", mapper)
                .authenticate("Bearer manager-test-token");
        ObjectNode arguments = mapper.createObjectNode().put("taskId", "WP-100")
                .put("idempotencyKey", "retry").put("expectedVersion", 1);
        controller.operatorCommand(principal, "retry_task", arguments);
        assertEquals("READY", runtime.task("WP-100").get("status").toString());
        assertNotEquals(previous, runtime.assignmentToken("WP-100"));
        assertEquals(List.of("WP-100"), runtime.readyTasks().stream().map(task -> task.get("id")).toList());
    }

    @Test
    void resultSerializationFailureFreezesWritesAndRecoversThePendingOperation() throws Exception {
        var brokenMapper = new ObjectMapper() {
            @Override public String writeValueAsString(Object value) throws com.fasterxml.jackson.core.JsonProcessingException {
                if (value instanceof java.util.Map<?, ?> map && map.containsKey("projection")) {
                    throw new com.fasterxml.jackson.core.JsonProcessingException("injected projection failure") {};
                }
                return super.writeValueAsString(value);
            }
        };
        brokenMapper.findAndRegisterModules();
        var interrupted = new ProjectMcpController(service, runtime, audit, brokenMapper);
        ObjectNode arguments = mapper.createObjectNode().put("taskId", "WP-100")
                .put("taskToken", runtime.assignmentToken("WP-100"))
                .put("idempotencyKey", "serialization-failure").put("expectedVersion", 1);
        arguments.putArray("affectedTaskIds").add("WP-130");
        ObjectNode params = mapper.createObjectNode().put("name", "publish_contract_change");
        params.set("arguments", arguments);
        interrupted.handle(new McpRequest("2.0", mapper.getNodeFactory().numberNode(1), "tools/call", params));
        assertEquals(1, runtime.contextVersion());
        assertEquals(true, runtime.integrationStatus().get("reconciliationRequired"));
        assertTrue(call("WP-100", "report_blocker", "after-serialization-failure", "{}").has("error"));
        assertTrue(audit.findByIdempotencyKey("after-serialization-failure").isEmpty());
        var recovered = new McpProjectRuntime(service, audit, mapper);
        assertEquals(2, recovered.contextVersion());
        assertEquals("COMPLETED", audit.findByIdempotencyKey("serialization-failure").orElseThrow().status());
    }

    @Test
    void failedJournalTerminationBlocksLaterWritesUntilRecovery() throws Exception {
        var failing = new AuditEventRepository(new JdbcTemplate(database)) {
            @Override public boolean fail(String key, long version) { return false; }
        };
        var interrupted = new ProjectMcpController(service, runtime, failing, mapper);
        ObjectNode arguments = mapper.createObjectNode().put("taskId", "WP-100")
                .put("taskToken", runtime.assignmentToken("WP-100"))
                .put("idempotencyKey", "failed-termination").put("expectedVersion", 1);
        arguments.putArray("affectedTaskIds").add("missing-task");
        ObjectNode params = mapper.createObjectNode().put("name", "publish_contract_change");
        params.set("arguments", arguments);
        interrupted.handle(new McpRequest("2.0", mapper.getNodeFactory().numberNode(1), "tools/call", params));
        assertEquals(true, runtime.integrationStatus().get("reconciliationRequired"));
        assertTrue(call("WP-100", "report_blocker", "later-write", "{}").has("error"));
        assertTrue(audit.findByIdempotencyKey("later-write").isEmpty());
        var recovered = new McpProjectRuntime(service, audit, mapper);
        assertEquals(false, recovered.integrationStatus().get("reconciliationRequired"));
        assertEquals("FAILED", audit.findByIdempotencyKey("failed-termination").orElseThrow().status());
    }

    @Test
    void semanticChangesInvalidateTheTransitiveDownstreamAndRejectVersionRollback() throws Exception {
        assertFalse(call("WP-100", "publish_contract_change", "v2", "{\"assetVersion\":2}").has("error"));
        assertEquals(false, runtime.task("WP-160").get("currentlyValid"));
        JsonNode rollback = call("WP-100", "publish_contract_change", "v1", "{\"assetVersion\":1}");
        assertTrue(rollback.has("error"));
        assertEquals(2, runtime.contextVersion());
    }

    @Test
    void legacySemanticVersionsDoNotPreventStartup() {
        for (String key : List.of("old-contract-first", "old-contract-second")) {
            audit.record(key, "WP-100", "publish_contract_change", """
                    {"taskId":"WP-100","assetVersion":1,"affectedTaskIds":["WP-120"],"idempotencyKey":"%s"}
                    """.formatted(key), 1);
            audit.complete(key, 1, "{}");
        }
        var recovered = assertDoesNotThrow(() -> new McpProjectRuntime(service, audit, mapper));
        assertEquals(3, recovered.contextVersion());
        assertEquals(false, recovered.task("WP-120").get("currentlyValid"));
    }

    @Test
    void legacyApprovalsAreRecoverableHistoryButCannotAuthorizeNewWork() throws Exception {
        String taskId = "WP-100";
        audit.record("legacy-claim", taskId, "claim_task", "{\"taskId\":\"WP-100\"}", 1);
        audit.complete("legacy-claim", 1, "{}");
        for (String status : List.of("RUNNING", "SUBMITTED", "CODE_REVIEW", "TESTING", "ACCEPTED")) {
            String key = "legacy-" + status;
            audit.record(key, taskId, "report_change_event", """
                    {"taskId":"WP-100","type":"TaskStatusChanged","targetStatus":"%s"}
                    """.formatted(status), 1);
            audit.complete(key, 1, "{}");
        }
        audit.record("legacy-gate", taskId, "report_change_event", """
                {"taskId":"WP-100","type":"QualityGateReported","role":"ENGINEERING_LEAD","gate":"CODE_REVIEW","passed":true}
                """, 1);
        audit.complete("legacy-gate", 1, "{}");
        var recovered = assertDoesNotThrow(() -> new McpProjectRuntime(service, audit, mapper));
        assertEquals("ACCEPTED", recovered.task(taskId).get("status").toString());
        assertEquals(false, recovered.task(taskId).get("currentlyValid"));
        assertTrue(recovered.readyTasks().isEmpty());
        assertEquals(recovered.task(taskId), new McpProjectRuntime(service, audit, mapper).task(taskId));
    }

    @Test
    void expiredPendingHeartbeatReachesATerminalJournalStateWithoutBlockingStartup() {
        String id = "4fce7fce-45dd-48f9-bfa2-88242e315188";
        audit.record("old-lease", "WP-100", "reserve_paths", "{\"taskId\":\"WP-100\"}", 1);
        audit.complete("old-lease", 1, """
                {"lease":{"leaseId":"%s","ownerId":"owner","taskExecutionId":"exec-WP-100",
                "resourcePath":"docs/contracts","mode":"EXCLUSIVE_WRITE","version":1,
                "startedAt":"2020-01-01T00:00:00Z","heartbeatAt":"2020-01-01T00:00:00Z",
                "expiresAt":"2020-01-01T00:05:00Z"}}
                """.formatted(id));
        audit.record("pending-heartbeat", "WP-100", "heartbeat", """
                {"taskId":"WP-100","leaseId":"%s","leaseVersion":1,"expectedVersion":1}
                """.formatted(id), 1);

        assertDoesNotThrow(() -> new McpProjectRuntime(service, audit, mapper));
        assertEquals("FAILED", audit.findByIdempotencyKey("pending-heartbeat").orElseThrow().status());
        assertDoesNotThrow(() -> new McpProjectRuntime(service, audit, mapper));
    }

    private JsonNode call(String task, String name, String key, String extra) throws Exception {
        ObjectNode arguments = (ObjectNode) mapper.readTree(extra);
        arguments.put("taskId", task).put("taskToken", runtime.assignmentToken(task))
                .put("expectedVersion", runtime.contextVersion()).put("idempotencyKey", key);
        ObjectNode params = mapper.createObjectNode().put("name", name);
        params.set("arguments", arguments);
        McpRequest request = new McpRequest("2.0", mapper.getNodeFactory().numberNode(1), "tools/call", params);
        JsonNode response = mapper.valueToTree(controller.handle(request).getBody());
        if (response.has("result")) McpToolSchemas.validate(mapper.valueToTree(McpToolSchemas.output(name)),
                response.path("result").path("structuredContent"), name);
        return response;
    }
}
