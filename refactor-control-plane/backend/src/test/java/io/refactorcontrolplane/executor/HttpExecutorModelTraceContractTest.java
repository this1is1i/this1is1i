package io.refactorcontrolplane.executor;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.refactorcontrolplane.executor.ExecutionProtocol.*;
import io.refactorcontrolplane.mcp.McpProjectRuntime.Execution;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;

class HttpExecutorModelTraceContractTest {
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    HttpServer server;
    ObjectNode response;
    HttpTaskExecutor executor;
    Request request;

    @BeforeEach void setup() throws Exception {
        request = new Request("a".repeat(64), "b".repeat(64), 1, 1, "developer", "http", "c".repeat(64),
                new Execution("TASK", "execution", "branch", ".", "d".repeat(40), null), Set.of("src"), Set.of(".git"), List.of(), Instant.now().plusSeconds(300));
        response = mapper.createObjectNode().put("executionId", "execution").put("requestHash", request.requestHash())
                .put("sequence", 2).put("state", "SUCCEEDED").put("commitSha", "e".repeat(40));
        response.putArray("checks");
        var model = response.putArray("modelRuns").addObject().put("id", "model-1").put("model", "internal-model")
                .put("promptVersion", "migration-v1").put("inputHash", "1".repeat(64)).put("contextHash", "2".repeat(64))
                .put("outputHash", "3".repeat(64)).put("totalTokens", 42);
        model.putArray("requestedTools").add("read_source"); model.putArray("toolCalls");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/executions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = mapper.writeValueAsBytes(response);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        }); server.start();
        executor = new HttpTaskExecutor(new ExecutorSettings.Definition("http", "HTTP",
                "http://127.0.0.1:" + server.getAddress().getPort(), "local-secret", 2, Map.of()), mapper);
    }
    @AfterEach void close() { if (server != null) server.stop(0); }

    @Test void preservesModelRequestDigestWithoutInventingToolCallsAndKeepsLegacyResultsReadable() {
        var observed = mapper.valueToTree(executor.query(request));
        assertEquals(response.path("modelRuns"), observed.path("modelRuns"));
        assertEquals(0, observed.path("modelRuns").get(0).path("toolCalls").size());
        response.remove("modelRuns");
        assertNotNull(executor.query(request));
    }
    @Test void rejectsFractionalTokensBeforeAnyNumericCoercion() {
        ((ObjectNode) response.path("modelRuns").get(0)).put("totalTokens", 1.5);
        assertThrows(IllegalArgumentException.class, () -> executor.query(request));
    }
    @Test void rejectsAmbiguousTraceIdsAndUnexpectedModelPayload() {
        ((ObjectNode) response.path("modelRuns").get(0)).put("sourceCode", "must never enter the journal");
        assertThrows(IllegalArgumentException.class, () -> executor.query(request));
    }
    @Test void rejectsModelReceiptsOnNonSubmittedStatesAndDuplicateCalls() {
        response.put("state", "RUNNING");
        assertThrows(IllegalArgumentException.class, () -> executor.query(request));
        response.put("state", "SUCCEEDED");
        ((com.fasterxml.jackson.databind.node.ArrayNode) response.path("modelRuns")).add(response.path("modelRuns").get(0).deepCopy());
        assertNull(executor.query(request), "Duplicate receipt IDs must never reach the journal");
    }
}
