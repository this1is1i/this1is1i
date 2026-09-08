package io.refactorcontrolplane.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class OpenAiCompatibleModelGateway implements ModelGateway {

    private final URI endpoint;
    private final String model;
    private final String apiKey;
    private final Duration timeout;
    private final ObjectMapper mapper;
    private final HttpClient client;

    public OpenAiCompatibleModelGateway(
            String baseUrl, String model, String apiKey, Duration timeout, ObjectMapper mapper,
            Set<String> allowedHosts) {
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/v1/chat/completions");
        if (endpoint.getHost() == null || allowedHosts == null || !allowedHosts.contains(endpoint.getHost())) {
            throw new IllegalArgumentException("Model gateway host is not in the enterprise allowlist");
        }
        boolean loopback = endpoint.getHost().equals("127.0.0.1") || endpoint.getHost().equals("localhost");
        if (!"https".equalsIgnoreCase(endpoint.getScheme()) && !(loopback && "http".equalsIgnoreCase(endpoint.getScheme()))) {
            throw new IllegalArgumentException("Model gateway must use HTTPS (HTTP is allowed only for loopback tests)");
        }
        this.model = model;
        this.apiKey = apiKey;
        this.timeout = timeout;
        this.mapper = mapper;
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public ModelExecution execute(ModelRequest request) {
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "model", model,
                    "messages", List.of(
                            Map.of("role", "system", "content", request.instruction()),
                            Map.of("role", "user", "content", request.codeContext()))));
            HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Model gateway returned HTTP " + response.statusCode());
            }
            JsonNode json = mapper.readTree(response.body());
            String output = json.path("choices").path(0).path("message").path("content").asText();
            if (output.isBlank()) throw new IllegalStateException("Model gateway returned an empty response");
            int totalTokens = json.path("usage").path("prompt_tokens").asInt()
                    + json.path("usage").path("completion_tokens").asInt();
            return new ModelExecution(output, new ModelAudit(
                    model, request.promptVersion(), ModelHashes.sha256(request.codeContext()),
                    ModelHashes.sha256(output), request.tools(), totalTokens));
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot encode or read model gateway response", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Model gateway call interrupted", exception);
        }
    }
}
