package io.refactorcontrolplane.governance;

import java.time.Instant;
import java.util.Map;

public record ChangeEvent(
        long sequence,
        String idempotencyKey,
        String type,
        String subject,
        Instant occurredAt,
        Map<String, String> details) {
    public ChangeEvent {
        details = Map.copyOf(details);
    }
}
