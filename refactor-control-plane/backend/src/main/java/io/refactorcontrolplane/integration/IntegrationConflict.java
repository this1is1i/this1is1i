package io.refactorcontrolplane.integration;

import java.util.Set;

public record IntegrationConflict(String kind, Set<String> tasks, String evidence) {
    public IntegrationConflict {
        tasks = Set.copyOf(tasks);
    }
}
