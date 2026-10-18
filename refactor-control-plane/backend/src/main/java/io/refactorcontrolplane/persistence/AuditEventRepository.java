package io.refactorcontrolplane.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AuditEventRepository {

    private final JdbcTemplate jdbc;
    private String scope = "";

    public AuditEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void requireProjectMode(boolean configured) {
        if (!configured && jdbc.queryForObject("SELECT COUNT(*) FROM project_plan", Long.class) != 0)
            throw new IllegalStateException("Configured project database cannot be opened in demo mode");
    }
    public void bindScope(String value) { scope = value; }

    public boolean record(
            String idempotencyKey, String taskId, String eventType, String payload, long expectedVersion) {
        try {
            jdbc.update("""
                    INSERT INTO audit_event
                        (idempotency_key, task_id, event_type, payload, status, version, plan_scope)
                    VALUES (?, ?, ?, ?, 'PENDING', ?, ?)
                    """, idempotencyKey, taskId, eventType, payload, expectedVersion, scope);
            return true;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    public boolean complete(String idempotencyKey, long expectedVersion, String result) {
        return jdbc.update("""
                UPDATE audit_event
                   SET status = 'COMPLETED', result = ?, version = version + 1, completed_at = CURRENT_TIMESTAMP
                 WHERE idempotency_key = ? AND version = ? AND status = 'PENDING' AND plan_scope = ?
                """, result, idempotencyKey, expectedVersion, scope) == 1;
    }

    public boolean fail(String idempotencyKey, long expectedVersion) {
        return jdbc.update("""
                UPDATE audit_event
                   SET status = 'FAILED', version = version + 1, completed_at = CURRENT_TIMESTAMP
                 WHERE idempotency_key = ? AND version = ? AND status = 'PENDING' AND plan_scope = ?
                """, idempotencyKey, expectedVersion, scope) == 1;
    }

    public List<PersistedAuditEvent> findAll() {
        return jdbc.query("""
                SELECT journal_sequence, idempotency_key, task_id, event_type, payload, result, status, version
                  FROM audit_event WHERE plan_scope = ? ORDER BY journal_sequence
                """, (row, number) -> new PersistedAuditEvent(
                row.getLong("journal_sequence"),
                row.getString("idempotency_key"),
                row.getString("task_id"),
                row.getString("event_type"),
                row.getString("payload"),
                row.getString("result"),
                row.getString("status"),
                row.getLong("version")), scope);
    }

    public Optional<PersistedAuditEvent> findByIdempotencyKey(String idempotencyKey) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE idempotency_key=? AND plan_scope<>?", Long.class, idempotencyKey, scope) > 0)
            throw new IllegalStateException("Idempotency key belongs to another plan revision");
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
