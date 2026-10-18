package io.refactorcontrolplane.governance;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.*;

class EvidenceStoreContractTest {
    final ObjectMapper mapper = new ObjectMapper();
    EmbeddedDatabase db;
    JdbcTemplate jdbc;
    EvidenceStore store;
    final EvidenceStore.Binding binding = new EvidenceStore.Binding("a".repeat(64), "TASK-1", "execution-1", "b".repeat(40), 1, 2, 3, 4);
    @BeforeEach void setup() {
        db = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build();
        jdbc = new JdbcTemplate(db); store = new EvidenceStore(jdbc, mapper);
    }
    @AfterEach void close() { db.shutdown(); }

    @Test void immutableContentReplaysAcrossRestartButDifferentActorOrContentCannotReuseKey() {
        var content = mapper.createObjectNode().put("conclusion", "检查通过").put("detail", "核对实际修改");
        var evidence = store.append("op-1", binding, "REVIEW", "lead", content);
        var reordered = mapper.createObjectNode().put("detail", "核对实际修改").put("conclusion", "检查通过");
        store = new EvidenceStore(jdbc, mapper);
        assertEquals(evidence, store.append("op-1", binding, "REVIEW", "lead", reordered));
        assertThrows(IllegalStateException.class, () -> store.append("op-1", binding, "REVIEW", "other", content));
        assertThrows(IllegalStateException.class, () -> store.append("op-1", binding, "REVIEW", "lead", content.deepCopy().put("detail", "changed")));
        content.put("detail", "caller-mutated");
        assertEquals("核对实际修改", store.read(binding.planScope(), evidence.id()).content().path("detail").asText());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM evidence_record", Integer.class));
    }

    @Test void missingTamperedAndWrongPlanEvidenceNeverLoads() {
        var evidence = store.append("op", binding, "REVIEW", "lead", mapper.createObjectNode().put("detail", "review"));
        assertThrows(IllegalArgumentException.class, () -> store.read(binding.planScope(), "0".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> store.read("c".repeat(64), evidence.id()));
        jdbc.update("UPDATE evidence_record SET payload=? WHERE id=?", "{}", evidence.id());
        assertThrows(IllegalStateException.class, () -> store.read(binding.planScope(), evidence.id()));
        assertThrows(IllegalStateException.class, () -> store.append("op", binding, "REVIEW", "lead", mapper.createObjectNode().put("detail", "review")));
    }

    @Test void bindingChangesAndOversizedContentAreRejectedBeforePublication() {
        var content = mapper.createObjectNode().put("detail", "review");
        var evidence = store.append("op", binding, "REVIEW", "lead", content);
        var changed = new EvidenceStore.Binding(binding.planScope(), binding.taskId(), "execution-2", binding.commitSha(), 2, 2, 3, 4);
        assertThrows(IllegalStateException.class, () -> store.append("op", changed, "REVIEW", "lead", content));
        assertThrows(IllegalArgumentException.class, () -> store.append("large", binding, "REVIEW", "lead", mapper.createObjectNode().put("detail", "中".repeat(65536))));
        assertEquals(binding, store.read(binding.planScope(), evidence.id()).binding());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM evidence_record", Integer.class));
    }

    @Test void decimalPrecisionSurvivesStorageAndIdempotentReplay() {
        var metric = new java.math.BigDecimal("0.12345678901234567890123456789");
        var content = mapper.createObjectNode().put("metric", metric).put("whole", new java.math.BigDecimal("1.00"))
                .put("ratio", 2.5).put("large", new java.math.BigInteger("123456789012345678901234567890"));
        var evidence = store.append("numbers", binding, "METRIC", "qa", content);
        store = new EvidenceStore(jdbc, mapper);
        var loaded = store.read(binding.planScope(), evidence.id());
        assertEquals(0, metric.compareTo(loaded.content().path("metric").decimalValue()));
        assertEquals(evidence, store.append("numbers", binding, "METRIC", "qa", content));
    }
}
