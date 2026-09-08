package io.refactorcontrolplane.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AuditEventRepository {

    private final JdbcTemplate jdbc;

    public AuditEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean record(
            String idempotencyKey, String taskId, String eventType, String payload, long expectedVersion) {
        try {
            jdbc.update("""
                    INSERT INTO audit_event
                        (idempotency_key, task_id, event_type, payload, status, version)
                    VALUES (?, ?, ?, ?, 'PENDING', ?)
                    """, idempotencyKey, taskId, eventType, payload, expectedVersion);
            return true;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    public boolean complete(String idempotencyKey, long expectedVersion, String result) {
        return jdbc.update("""
                UPDATE audit_event
                   SET status = 'COMPLETED', result = ?, version = version + 1, completed_at = CURRENT_TIMESTAMP
                 WHERE idempotency_key = ? AND version = ? AND status = 'PENDING'
                """, result, idempotencyKey, expectedVersion) == 1;
    }

    public boolean fail(String idempotencyKey, long expectedVersion) {
        return jdbc.update("""
                UPDATE audit_event
                   SET status = 'FAILED', version = version + 1, completed_at = CURRENT_TIMESTAMP
                 WHERE idempotency_key = ? AND version = ? AND status = 'PENDING'
                """, idempotencyKey, expectedVersion) == 1;
    }

    public List<PersistedAuditEvent> findAll() {
        return jdbc.query("""
                SELECT journal_sequence, idempotency_key, task_id, event_type, payload, result, status, version
                  FROM audit_event ORDER BY journal_sequence
                """, (row, number) -> new PersistedAuditEvent(
                row.getLong("journal_sequence"),
                row.getString("idempotency_key"),
                row.getString("task_id"),
                row.getString("event_type"),
                row.getString("payload"),
                row.getString("result"),
                row.getString("status"),
                row.getLong("version")));
    }

    public Optional<PersistedAuditEvent> findByIdempotencyKey(String idempotencyKey) {
        return jdbc.query("""
                SELECT journal_sequence, idempotency_key, task_id, event_type, payload, result, status, version
                  FROM audit_event WHERE idempotency_key = ?
                """, (row, number) -> new PersistedAuditEvent(
                row.getLong("journal_sequence"),
                row.getString("idempotency_key"),
                row.getString("task_id"),
                row.getString("event_type"),
                row.getString("payload"),
                row.getString("result"),
                row.getString("status"),
                row.getLong("version")), idempotencyKey).stream().findFirst();
    }
}
