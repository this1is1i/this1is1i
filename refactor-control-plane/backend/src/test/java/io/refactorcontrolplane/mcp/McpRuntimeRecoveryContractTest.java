package io.refactorcontrolplane.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.refactorcontrolplane.api.DemoProjectService;
import io.refactorcontrolplane.persistence.AuditEventRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

class McpRuntimeRecoveryContractTest {

    @Test
    void rebuildsSemanticStateAndActiveLeasesFromTheOperationJournal() throws Exception {
        EmbeddedDatabase database = database();
        try {
            ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
            AuditEventRepository audit = new AuditEventRepository(new JdbcTemplate(database));
            DemoProjectService projectService = new DemoProjectService("../samples/workspaces/springmvc-blog");
            McpProjectRuntime first = new McpProjectRuntime(projectService, audit, mapper);
            ProjectMcpController firstController = new ProjectMcpController(projectService, first, audit, mapper);

            call(firstController, mapper, "reserve_paths", "recovery-lease", "WP-100",
                    first.assignmentToken("WP-100"), """
                    ,"ownerId":"dev-a","resourcePath":"docs/contracts/articles.openapi.yaml"
                    """);
            call(firstController, mapper, "publish_contract_change", "recovery-contract", "WP-100",
                    first.assignmentToken("WP-100"), ",\"affectedTaskIds\":[\"WP-120\"]");

            McpProjectRuntime recovered = new McpProjectRuntime(projectService, audit, mapper);

            assertEquals(2, recovered.contextVersion());
            assertEquals("STALE", recovered.task("WP-120").get("status").toString());
            List<?> activeLeases = (List<?>) recovered.integrationStatus().get("activeLeases");
            assertEquals(1, activeLeases.size());
            assertFalse(audit.findAll().stream().anyMatch(event -> event.result() == null));
        } finally {
            database.shutdown();
        }
    }

    @Test
    void completesAPendingInMemoryOperationDuringRestartRecovery() throws Exception {
        EmbeddedDatabase database = database();
        try {
            ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
            AuditEventRepository audit = new AuditEventRepository(new JdbcTemplate(database));
            String payload = "{\"expectedVersion\":1,\"idempotencyKey\":\"pending-blocker\","
                    + "\"taskId\":\"WP-130\",\"tool\":\"report_blocker\"}";
            audit.record("pending-blocker", "WP-130", "report_blocker", payload, 1);

            McpProjectRuntime recovered = new McpProjectRuntime(
                    new DemoProjectService("../samples/workspaces/springmvc-blog"), audit, mapper);

            assertEquals("BLOCKED", recovered.task("WP-130").get("status").toString());
            assertEquals("COMPLETED", audit.findByIdempotencyKey("pending-blocker").orElseThrow().status());
        } finally {
            database.shutdown();
        }
    }

    @Test
    void replaysDependentOperationsInJournalOrderEvenWhenKeysSortInReverse() {
        EmbeddedDatabase database = database();
        try {
            ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
            JdbcTemplate jdbc = new JdbcTemplate(database);
            AuditEventRepository audit = new AuditEventRepository(jdbc);
            String claim = "{\"expectedVersion\":1,\"idempotencyKey\":\"z-claim\","
                    + "\"taskId\":\"WP-130\",\"tool\":\"claim_task\"}";
            String running = "{\"expectedVersion\":1,\"idempotencyKey\":\"a-running\","
                    + "\"targetStatus\":\"RUNNING\",\"taskId\":\"WP-130\","
                    + "\"tool\":\"report_change_event\",\"type\":\"TaskStatusChanged\"}";
            audit.record("z-claim", "WP-130", "claim_task", claim, 1);
            audit.complete("z-claim", 1, "{}");
            audit.record("a-running", "WP-130", "report_change_event", running, 1);
            audit.complete("a-running", 1, "{}");
            jdbc.update("UPDATE audit_event SET created_at = TIMESTAMP WITH TIME ZONE '2026-01-01 00:00:00Z'");

            McpProjectRuntime recovered = new McpProjectRuntime(
                    new DemoProjectService("../samples/workspaces/springmvc-blog"), audit, mapper);

            assertEquals("RUNNING", recovered.task("WP-130").get("status").toString());
        } finally {
            database.shutdown();
        }
    }

    private static EmbeddedDatabase database() {
        return new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .addScript("schema.sql")
                .addScript("db/migration/V1__create_modern_articles.sql")
                .build();
    }

    private static void call(ProjectMcpController controller, ObjectMapper mapper, String tool,
            String key, String taskId, String token, String extra) throws Exception {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"" + tool + "\",\"arguments\":{"
                + "\"taskToken\":\"" + token + "\",\"idempotencyKey\":\"" + key + "\","
                + "\"expectedVersion\":1,\"taskId\":\"" + taskId + "\"" + extra + "}}}";
        var response = controller.handle(mapper.readValue(json, McpRequest.class));
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        if (body == null || body.containsKey("error")) throw new AssertionError("MCP call failed: " + body);
    }
}
