package io.refactorcontrolplane.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

class AuditEventRepositoryContractTest {

    @Test
    void persistsIdempotentEventsAndUsesExpectedVersionForCompletion() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .addScript("schema.sql")
                .build();
        try {
            AuditEventRepository repository = new AuditEventRepository(new JdbcTemplate(database));

            assertTrue(repository.record("event-1", "WP-120", "CommitSubmitted", "{}", 1));
            assertFalse(repository.record("event-1", "WP-120", "CommitSubmitted", "{}", 1));
            assertFalse(repository.complete("event-1", 99, "{\"ok\":true}"));
            assertTrue(repository.complete("event-1", 1, "{\"ok\":true}"));

            assertEquals(1, repository.findAll().size());
            assertEquals("COMPLETED", repository.findAll().getFirst().status());
            assertEquals(2, repository.findAll().getFirst().version());
            assertEquals("{\"ok\":true}", repository.findAll().getFirst().result());
        } finally {
            database.shutdown();
        }
    }

    @Test
    void replaysByDatabaseJournalSequenceWhenTimestampsAndKeysCannotExpressExecutionOrder() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .addScript("schema.sql")
                .build();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            AuditEventRepository repository = new AuditEventRepository(jdbc);

            assertTrue(repository.record("z-first", "WP-100", "claim_task", "{}", 1));
            assertTrue(repository.record("a-second", "WP-100", "report_change_event", "{}", 1));
            jdbc.update("UPDATE audit_event SET created_at = TIMESTAMP WITH TIME ZONE '2026-01-01 00:00:00Z'");

            var events = repository.findAll();
            assertEquals(List.of("z-first", "a-second"),
                    events.stream().map(PersistedAuditEvent::idempotencyKey).toList());
            assertTrue(events.get(0).journalSequence() < events.get(1).journalSequence());
        } finally {
            database.shutdown();
        }
    }
}
