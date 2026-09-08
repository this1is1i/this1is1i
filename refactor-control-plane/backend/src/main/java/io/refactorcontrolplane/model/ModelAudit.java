package io.refactorcontrolplane.model;

import java.util.List;

public record ModelAudit(
        String model,
        String promptVersion,
        String contextHash,
        String outputHash,
        List<String> tools,
        int totalTokens) {
    public ModelAudit {
        tools = List.copyOf(tools);
    }
}
