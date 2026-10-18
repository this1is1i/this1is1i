package io.refactorcontrolplane.executor;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** A trusted executor's receipt, not a platform attestation of the model or tool execution. */
public record ExecutorModelRun(String id, String model, String promptVersion, String inputHash,
        String contextHash, String outputHash, int totalTokens, List<String> requestedTools, List<ToolCall> toolCalls) {
    public record ToolCall(String id, String name, String inputHash, String outputHash, String state) {
        public ToolCall {
            text(id, 100); text(name, 100); hash(inputHash); hash(outputHash);
            if (!Set.of("SUCCEEDED", "FAILED").contains(state)) throw new IllegalArgumentException("Invalid tool call state");
        }
    }
    public ExecutorModelRun {
        text(id, 100); text(model, 200); text(promptVersion, 100); hash(inputHash); hash(contextHash); hash(outputHash);
        if (totalTokens < 0 || requestedTools == null || requestedTools.size() > 50 || toolCalls == null || toolCalls.size() > 50)
            throw new IllegalArgumentException("Model receipt exceeds its bounds");
        requestedTools.forEach(tool -> text(tool, 100));
        requestedTools = List.copyOf(requestedTools); toolCalls = List.copyOf(toolCalls);
        if (toolCalls.stream().map(ToolCall::id).distinct().count() != toolCalls.size())
            throw new IllegalArgumentException("Duplicate tool call ID");
    }
    public static void validate(JsonNode result) {
        if (!result.has("modelRuns") || result.path("modelRuns").isNull()) return;
        var runs = result.path("modelRuns");
        if (!runs.isArray() || runs.size() > 20) throw new IllegalArgumentException("Invalid model receipts");
        if (!runs.isEmpty() && !result.path("state").asText().equals("SUCCEEDED"))
            throw new IllegalArgumentException("Model receipts require a successful submitted execution");
        for (var run : runs) {
            fields(run, Set.of("id", "model", "promptVersion", "inputHash", "contextHash", "outputHash", "totalTokens", "requestedTools", "toolCalls"));
            for (String field : List.of("id", "model", "promptVersion", "inputHash", "contextHash", "outputHash"))
                if (!run.path(field).isTextual()) throw new IllegalArgumentException("Model receipt requires text fields");
            var tokens = run.path("totalTokens");
            if (!tokens.isIntegralNumber() || !tokens.canConvertToInt() || tokens.intValue() < 0)
                throw new IllegalArgumentException("Model tokens must be an exact nonnegative integer");
            if (!run.path("requestedTools").isArray() || run.path("requestedTools").size() > 50
                    || !run.path("toolCalls").isArray() || run.path("toolCalls").size() > 50)
                throw new IllegalArgumentException("Invalid model tool lists");
            for (var tool : run.path("requestedTools")) if (!tool.isTextual()) throw new IllegalArgumentException("Requested tool must be text");
            for (var call : run.path("toolCalls")) {
                fields(call, Set.of("id", "name", "inputHash", "outputHash", "state"));
                for (String field : List.of("id", "name", "inputHash", "outputHash", "state"))
                    if (!call.path(field).isTextual()) throw new IllegalArgumentException("Tool receipt requires text fields");
            }
        }
    }
    private static void fields(JsonNode value, Set<String> allowed) {
        if (!value.isObject() || value.properties().stream().anyMatch(field -> !allowed.contains(field.getKey())))
            throw new IllegalArgumentException("Unexpected model receipt fields");
    }
    private static void text(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) throw new IllegalArgumentException("Invalid model receipt text");
    }
    private static void hash(String value) {
        if (value == null || !value.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Model receipt requires SHA-256 digests");
    }
}
