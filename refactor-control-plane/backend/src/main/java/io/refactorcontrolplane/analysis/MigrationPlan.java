package io.refactorcontrolplane.analysis;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record MigrationPlan(
        String baseCommit,
        String targetArchitectureMermaid,
        List<PlanWorkPackage> workPackages,
        List<String> criticalPath,
        Map<String, List<String>> parallelExecutionGroups,
        String apiContract,
        String databaseCompatibility,
        Map<String, String> functionalParity,
        Set<String> requiredEvidence) {
    public MigrationPlan {
        workPackages = List.copyOf(workPackages);
        criticalPath = List.copyOf(criticalPath);
        parallelExecutionGroups = Map.copyOf(parallelExecutionGroups);
        functionalParity = Map.copyOf(functionalParity);
        requiredEvidence = Set.copyOf(requiredEvidence);
    }
}
