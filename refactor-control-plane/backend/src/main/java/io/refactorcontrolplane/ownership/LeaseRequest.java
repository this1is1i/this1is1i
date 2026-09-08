package io.refactorcontrolplane.ownership;

import java.time.Duration;
import java.util.Objects;

public record LeaseRequest(
        String ownerId,
        String taskExecutionId,
        String resourcePath,
        OwnershipMode mode,
        Duration ttl) {

    public LeaseRequest {
        ownerId = requireText(ownerId, "ownerId");
        taskExecutionId = requireText(taskExecutionId, "taskExecutionId");
        resourcePath = requireText(resourcePath, "resourcePath");
        mode = Objects.requireNonNull(mode, "mode must not be null");
        ttl = Objects.requireNonNull(ttl, "ttl must not be null");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
