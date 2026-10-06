package io.refactorcontrolplane.mcp;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:mcp-schema-test")
@AutoConfigureMockMvc
class McpSchemaContractTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired McpProjectRuntime runtime;
    @Autowired io.refactorcontrolplane.persistence.AuditEventRepository audit;

    @Test void everyToolDeclaresSpecificInputAndOutputContracts() throws Exception {
        JsonNode tools = rpc("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}").path("result").path("tools");
        assertEquals(15, tools.size());
        for (JsonNode tool : tools) {
            assertTrue(tool.path("inputSchema").has("properties"), tool.path("name").asText());
            assertEquals("object", tool.path("outputSchema").path("type").asText());
            assertTrue(tool.path("outputSchema").path("required").size() > 0);
        }
    }

    @Test void malformedArgumentsFailBeforeAnyDomainWrite() throws Exception {
        String[] invalid = {"{\"taskId\":23}", "{\"taskId\":\"WP-100\",\"unexpected\":true}", "[]", "null"};
        for (String args : invalid) {
            var result = rpc("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"get_task_package\",\"arguments\":" + args + "}}");
            assertEquals(-32602, result.path("error").path("code").asInt(), result.toString());
        }
    }

    @Test void readToolResultsMatchTheirPublishedSchemas() throws Exception {
        for (String name : new String[]{"get_project_context", "get_ready_tasks", "get_task_package",
                "get_dependency_impact", "get_integration_status"}) {
            String arguments = name.equals("get_task_package") ? "{\"taskId\":\"WP-100\"}" : "{}";
            var result = rpc("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\""
                    + name + "\",\"arguments\":" + arguments + "}}");
            assertFalse(result.has("error"), result.toString());
            McpToolSchemas.validate(mapper.valueToTree(McpToolSchemas.output(name)), result.path("result").path("structuredContent"), name);
        }
    }

    @Test void excessiveLeaseDurationIsRejectedBeforeJournaling() throws Exception {
        var args = mapper.createObjectNode().put("taskId", "WP-100").put("taskToken", runtime.assignmentToken("WP-100"))
                .put("expectedVersion", runtime.contextVersion()).put("idempotencyKey", "ttl-overflow")
                .put("ownerId", "developer").put("resourcePath", "docs/contracts").put("ttlSeconds", Long.MAX_VALUE);
        var result = rpc("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"reserve_paths\",\"arguments\":" + args + "}}");
        assertEquals(-32602, result.path("error").path("code").asInt());
        assertTrue(audit.findByIdempotencyKey("ttl-overflow").isEmpty());
    }

    @Test void schemaPatternsCompileInJavaScriptClients() throws Exception {
        var tools = rpc("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}").path("result").path("tools");
        var file = temporary.resolve("schemas.json");
        java.nio.file.Files.writeString(file, tools.toString(), java.nio.charset.StandardCharsets.UTF_8);
        var process = new ProcessBuilder("node", "../scripts/validate-schema-patterns.mjs", file.toString())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
    }

    @Test void unicodeIdempotencyKeysFitThePublishedContractAndDatabase() throws Exception {
        String key = "🔑".repeat(200);
        var args = mapper.createObjectNode().put("taskId", "WP-100").put("taskToken", runtime.assignmentToken("WP-100"))
                .put("expectedVersion", runtime.contextVersion()).put("idempotencyKey", key).put("type", "AgentStatusChanged");
        var result = rpc("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"report_change_event\",\"arguments\":" + args + "}}");
        assertFalse(result.has("error"), result.toString());
        assertEquals("COMPLETED", audit.findByIdempotencyKey(key).orElseThrow().status());
        assertEquals(false, runtime.integrationStatus().get("reconciliationRequired"));
    }

    private JsonNode rpc(String payload) throws Exception {
        return mapper.readTree(mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andReturn().getResponse().getContentAsString());
    }
}
