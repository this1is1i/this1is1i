package io.refactorcontrolplane.ownership;

import java.util.UUID;

public record LeaseConflict(
        UUID leaseId,
        String taskExecutionId,
        String resourcePath,
        OwnershipMode mode,
        String reason) {
}
