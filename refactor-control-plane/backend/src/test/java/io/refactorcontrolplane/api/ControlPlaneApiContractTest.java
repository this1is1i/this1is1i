package io.refactorcontrolplane.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.refactorcontrolplane.persistence.AuditEventRepository;
import io.refactorcontrolplane.mcp.McpProjectRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ControlPlaneApiContractTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AuditEventRepository auditEvents;

    @Autowired
    private McpProjectRuntime runtime;

    @Test
    void exposesEvidenceBackedOverviewForThePinnedDemoProject() throws Exception {
        mvc.perform(get("/api/v1/demo/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value("legacy-blog"))
                .andExpect(jsonPath("$.analysis.snapshotCommit")
                        .value("5e62608af333b5757bf767e05b19e2b0d56f0afb"))
                .andExpect(jsonPath("$.analysis.evidence.length()").value(4))
                .andExpect(jsonPath("$.plan.workPackages.length()").value(7))
                .andExpect(jsonPath("$.mergePolicy").value("HUMAN_ONLY"));
    }

    @Test
    void implementsMcpInitializationAndTheApprovedToolCatalog() throws Exception {
        mvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.protocolVersion").value("2025-03-26"));

        mvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.tools.length()").value(15))
                .andExpect(jsonPath("$.result.tools[0].name").value("get_project_context"));

        mvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","method":"notifications/initialized"}
                                """))
                .andExpect(status().isAccepted());

        mvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":9,"method":"tools/call"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32602));
    }

    @Test
    void mcpWriteToolsRequireTaskTokenIdempotencyKeyAndExpectedVersion() throws Exception {
        mvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":3,"method":"tools/call",
                                 "params":{"name":"report_change_event","arguments":{}}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32001));

        mvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":4,"method":"tools/call",
                                 "params":{"name":"report_change_event","arguments":{
                                   "taskToken":"%s","idempotencyKey":"event-1",
                                   "expectedVersion":1,"taskId":"WP-120","type":"AgentStatusChanged"}}}
                                """.formatted(runtime.assignmentToken("WP-120"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[0].type").value("text"))
                .andExpect(jsonPath("$.result.structuredContent.recorded").value(true));

        String persistedPayload = auditEvents.findAll().stream()
                .filter(event -> event.idempotencyKey().equals("event-1"))
                .findFirst()
                .orElseThrow()
                .payload();
        assertFalse(persistedPayload.contains(runtime.assignmentToken("WP-120")),
                "task tokens must never be persisted");

        mvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(writeCall(5, "submit_changeset", "event-1", "WP-120",
                                runtime.assignmentToken("WP-120"),
                                "\"changedPaths\":[\"backend/src/main/java\"]")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32003));

        mvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":6,"method":"tools/call",
                                 "params":{"name":"report_change_event","arguments":{
                                   "taskToken":"%s","idempotencyKey":"bad-version",
                                   "expectedVersion":99,"taskId":"WP-120","type":"AgentStatusChanged"}}}
                                """.formatted(runtime.assignmentToken("WP-120"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32003));
    }

    @Test
    void mcpWritesValidateTokensDriveLeasesAndPropagateSemanticChanges() throws Exception {
        mvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(writeCall(10, "reserve_paths", "lease-a", "WP-100",
                                runtime.assignmentToken("WP-100"), "\"ownerId\":\"dev-a\","
                                        + "\"resourcePath\":\"docs/contracts/articles.openapi.yaml\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.structuredContent.accepted").value(true));

        mvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(writeCall(11, "reserve_paths", "lease-b", "WP-110",
                                runtime.assignmentToken("WP-110"), "\"ownerId\":\"dev-b\","
                                        + "\"resourcePath\":\"docs/contracts/articles.openapi.yaml\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.structuredContent.accepted").value(false));

        mvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(writeCall(12, "publish_contract_change", "contract-change", "WP-100",
                                "invalid-token", "\"affectedTaskIds\":[\"WP-120\"]")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32002));

        mvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(writeCall(13, "publish_contract_change", "contract-change-valid", "WP-100",
                                runtime.assignmentToken("WP-100"),
                                "\"affectedTaskIds\":[\"WP-120\"]")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.structuredContent.contextVersion").value(2));

        mvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":14,"method":"tools/call",
                                 "params":{"name":"get_task_package","arguments":{"taskId":"WP-120"}}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.structuredContent.status").value("STALE"));
    }

    private static String writeCall(
            int id, String tool, String key, String taskId, String token, String extraArguments) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"" + tool + "\",\"arguments\":{"
                + "\"taskToken\":\"" + token + "\",\"idempotencyKey\":\"" + key + "\","
                + "\"expectedVersion\":1,\"taskId\":\"" + taskId + "\"," + extraArguments + "}}}";
    }
}
