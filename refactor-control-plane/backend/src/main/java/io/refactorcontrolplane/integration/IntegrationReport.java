package io.refactorcontrolplane.integration;

import java.util.List;
import java.util.Set;

public record IntegrationReport(
        List<IntegrationConflict> conflicts,
        Set<String> affectedTasks,
        List<String> suggestedOrder,
        boolean automaticallyMergeMain) {
    public IntegrationReport {
        conflicts = List.copyOf(conflicts);
        affectedTasks = Set.copyOf(affectedTasks);
        suggestedOrder = List.copyOf(suggestedOrder);
    }
}
