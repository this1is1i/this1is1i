package io.refactorcontrolplane.ownership;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PathLease(
        UUID leaseId,
        String ownerId,
        String taskExecutionId,
        String resourcePath,
        OwnershipMode mode,
        long version,
        Instant startedAt,
        Instant heartbeatAt,
        Instant expiresAt) {

    public PathLease {
        Objects.requireNonNull(leaseId, "leaseId must not be null");
        Objects.requireNonNull(ownerId, "ownerId must not be null");
        Objects.requireNonNull(taskExecutionId, "taskExecutionId must not be null");
        Objects.requireNonNull(resourcePath, "resourcePath must not be null");
        Objects.requireNonNull(mode, "mode must not be null");
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        Objects.requireNonNull(heartbeatAt, "heartbeatAt must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive");
        }
    }

    public boolean isActiveAt(Instant instant) {
        return instant.isBefore(expiresAt);
    }
}
