package io.refactorcontrolplane.mcp;

import com.fasterxml.jackson.databind.JsonNode;

public record McpRequest(String jsonrpc, JsonNode id, String method, JsonNode params) {
}
