package io.refactorcontrolplane.persistence;

public record PersistedAuditEvent(
        long journalSequence,
        String idempotencyKey,
        String taskId,
        String eventType,
        String payload,
        String result,
        String status,
        long version) {
}
