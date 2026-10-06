package io.refactorcontrolplane.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Repository
public class IntegrationStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;

    public IntegrationStore(JdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.mapper = mapper; this.transactions = new TransactionTemplate(manager);
    }

    public record Entry(String id, String kind, String key, String actor, String request, String payload, long revision) {}

    public Optional<Entry> byKey(String key) { return find("operation_key", key); }
    public Entry get(String id) { return find("id", id).orElseThrow(() -> new IllegalArgumentException("Unknown integration record")); }
    private Optional<Entry> find(String column, String value) {
        return jdbc.query("SELECT * FROM integration_record WHERE " + column + "=?", (rs, n) -> new Entry(
                rs.getString("id"), rs.getString("kind"), rs.getString("operation_key"), rs.getString("actor"),
                rs.getString("request_json"), rs.getString("payload"), rs.getLong("revision")), value).stream().findFirst();
    }
    public List<String> candidates() {
        return jdbc.queryForList("SELECT id FROM integration_record WHERE kind='candidate' ORDER BY id", String.class);
    }
    public boolean enrolled(String taskId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM integration_member WHERE task_id=?", Long.class, taskId) > 0;
    }
    public Entry insert(String id, String kind, String key, String actor, Object request, Object payload, List<String> tasks) {
        String requestJson = json(request), payloadJson = json(payload);
        transactions.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO integration_record(id,kind,operation_key,actor,request_json,payload) VALUES(?,?,?,?,?,?)",
                    id, kind, key, actor, requestJson, payloadJson);
            tasks.forEach(task -> jdbc.update("INSERT INTO integration_member(candidate_id,task_id) VALUES(?,?)", id, task));
        });
        return get(id);
    }
    public Entry save(Entry before, Object payload) {
        if (jdbc.update("UPDATE integration_record SET payload=?,revision=revision+1 WHERE id=? AND revision=?",
                json(payload), before.id(), before.revision()) != 1) throw new IllegalStateException("Integration record changed concurrently");
        return get(before.id());
    }
    public Entry beginValidation(Entry candidateBefore, IntegrationService.Candidate candidate,
            IntegrationService.Validation running, String key, String actor, Object request) {
        return transactions.execute(status -> {
            Entry attempt = insert(running.id(), "validation", key, actor, request, running, List.of());
            save(candidateBefore, new IntegrationService.Candidate(candidate.plan(), candidate.state(), candidate.assembly(), running.id()));
            return attempt;
        });
    }
    public String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (java.io.IOException ex) { throw new IllegalStateException("Cannot serialize integration evidence", ex); }
    }
    public <T> T read(Entry entry, Class<T> type) {
        try { return mapper.readValue(entry.payload(), type); }
        catch (java.io.IOException ex) { throw new IllegalStateException("Cannot read integration evidence", ex); }
    }
}
