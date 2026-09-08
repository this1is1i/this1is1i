package io.refactorcontrolplane.model;

import java.util.List;

public record ModelRequest(
        String promptVersion,
        String codeContext,
        String instruction,
        List<String> tools) {
    public ModelRequest {
        tools = List.copyOf(tools);
    }
}
