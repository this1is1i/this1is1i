package io.refactorcontrolplane.analysis;

import java.util.List;
import java.util.Set;

public record PlanWorkPackage(
        String id,
        String title,
        String role,
        Set<String> dependencies,
        Set<String> allowedPaths,
        int estimatedHours,
        String workingDirectory,
        List<String> acceptanceCommands) {
    public PlanWorkPackage {
        dependencies = Set.copyOf(dependencies);
        allowedPaths = Set.copyOf(allowedPaths);
        if (workingDirectory == null || workingDirectory.isBlank()) {
            throw new IllegalArgumentException("workingDirectory must not be blank");
        }
        acceptanceCommands = List.copyOf(acceptanceCommands);
    }
}
