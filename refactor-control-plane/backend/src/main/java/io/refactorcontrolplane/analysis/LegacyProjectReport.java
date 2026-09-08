package io.refactorcontrolplane.analysis;

import java.util.List;
import java.util.Set;

public record LegacyProjectReport(
        String snapshotCommit,
        Set<String> technologyStack,
        List<ModuleInsight> modules,
        List<Evidence> evidence,
        String currentArchitectureMermaid,
        List<String> risks,
        List<String> upgradePoints) {
    public LegacyProjectReport {
        technologyStack = Set.copyOf(technologyStack);
        modules = List.copyOf(modules);
        evidence = List.copyOf(evidence);
        risks = List.copyOf(risks);
        upgradePoints = List.copyOf(upgradePoints);
    }
}
