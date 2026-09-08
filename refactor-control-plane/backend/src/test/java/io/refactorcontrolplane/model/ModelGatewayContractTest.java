package io.refactorcontrolplane.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ModelGatewayContractTest {

    @Test
    void mockExecutorProducesTraceableDeterministicOutputWithoutSourceInAudit() {
        ModelRequest request = new ModelRequest(
                "prompt-v3", "class Legacy {}", "Generate a patch", List.of("read_file"));

        ModelExecution execution = new MockModelGateway().execute(request);

        assertTrue(execution.output().contains("mock patch"));
        assertEquals("mock-enterprise-model", execution.audit().model());
        assertEquals("prompt-v3", execution.audit().promptVersion());
        assertEquals(64, execution.audit().contextHash().length());
        assertFalse(execution.audit().toString().contains("class Legacy"));
    }

    @Test
    void httpExecutorUsesOpenAiCompatibleEndpointAndKeepsSecretOutOfResult() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = ("{\"choices\":[{\"message\":{\"content\":\"patch-output\"}}],"
                    + "\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            String secret = "internal-secret";
            ModelGateway gateway = new OpenAiCompatibleModelGateway(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "enterprise-code-model", secret, Duration.ofSeconds(5), new ObjectMapper(),
                    Set.of("127.0.0.1"));

            ModelExecution execution = gateway.execute(new ModelRequest(
                    "prompt-v1", "package internal;", "Review", List.of("search")));

            assertEquals("Bearer " + secret, authorization.get());
            assertTrue(body.get().contains("enterprise-code-model"));
            assertEquals("patch-output", execution.output());
            assertEquals(15, execution.audit().totalTokens());
            assertFalse(execution.toString().contains(secret));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsEndpointsOutsideTheExplicitEnterpriseAllowlist() {
        assertThrows(IllegalArgumentException.class, () -> new OpenAiCompatibleModelGateway(
                "https://api.public-model.example", "model", "secret", Duration.ofSeconds(5),
                new ObjectMapper(), Set.of("models.corp.example")));
    }
}
