package io.refactorcontrolplane.integration;

import java.util.Set;

public record ChangesetDescriptor(
        String taskId,
        Set<String> changedPaths,
        long apiContractVersion,
        long schemaVersion) {
    public ChangesetDescriptor {
        changedPaths = Set.copyOf(changedPaths);
    }
}
