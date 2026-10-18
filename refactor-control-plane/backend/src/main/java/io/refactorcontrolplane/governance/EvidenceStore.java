package io.refactorcontrolplane.governance;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Append-only internal storage. Integrity does not establish that an author's claim is true. */
@Repository
public class EvidenceStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public EvidenceStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        // JSON metrics must round-trip without conversion to binary floating point before hashing.
        this.mapper = mapper.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    public record Binding(String planScope, String taskId, String executionId, String commitSha,
            long contextVersion, long contractVersion, long schemaVersion, long adrVersion) {
        public Binding {
            if (planScope == null || (!planScope.isEmpty() && !planScope.matches("[a-f0-9]{64}"))
                    || !text(taskId, 100) || !text(executionId, 100) || commitSha == null || !commitSha.matches("[a-f0-9]{40}|[a-f0-9]{64}")
                    || contextVersion < 1 || contractVersion < 1 || schemaVersion < 1 || adrVersion < 1)
                throw new IllegalArgumentException("Evidence requires an exact execution, commit and semantic binding");
        }
    }
    public record Evidence(String id, String operationKey, Binding binding, String kind, String author, String createdAt, JsonNode content) {
        public Evidence { content = content.deepCopy(); }
        @Override public JsonNode content() { return content.deepCopy(); }
    }
    private record Document(String operationKey, Binding binding, String kind, String author, String createdAt, JsonNode content) {}
    private record Row(String id, String scope, String key, String requestHash, String payload) {}

    public void validateAppend(String key, Binding binding, String kind, String author, JsonNode content) {
        validated(key, binding, kind, author, content);
    }
    public void validateContent(JsonNode content) { frozenContent(content); }
    private JsonNode validated(String key, Binding binding, String kind, String author, JsonNode content) {
        if (!text(key, 160) || binding == null || kind == null || !kind.matches("[A-Z_]{1,50}")
                || !text(author, 200) || content == null || !content.isObject() || content.isEmpty())
            throw new IllegalArgumentException("Evidence requires a key, binding, kind, author and object content");
        return frozenContent(content);
    }
    private JsonNode frozenContent(JsonNode content) {
        if (content == null || !content.isObject() || content.isEmpty()) throw new IllegalArgumentException("Evidence requires object content");
        JsonNode frozen = canonical(content, 0);
        if (json(frozen).getBytes(StandardCharsets.UTF_8).length > 65536)
            throw new IllegalArgumentException("Evidence content exceeds 64 KiB");
        return frozen;
    }
    public Evidence append(String key, Binding binding, String kind, String author, JsonNode content) {
        JsonNode frozen = validated(key, binding, kind, author, content);
        String requestHash = requestHash(binding, kind, author, frozen);
        var existing = byKey(binding.planScope(), key);
        if (existing.isPresent()) return replay(existing.get(), requestHash);
        String payload = json(canonical(mapper.valueToTree(new Document(key, binding, kind, author, Instant.now().toString(), frozen)), 0));
        String id = hash(payload);
        try {
            jdbc.update("INSERT INTO evidence_record(id,plan_scope,operation_key,request_hash,payload) VALUES(?,?,?,?,?)",
                    id, binding.planScope(), key, requestHash, payload);
        } catch (DuplicateKeyException ex) {
            return replay(byKey(binding.planScope(), key).orElseThrow(() -> new IllegalStateException("Evidence identity conflict")), requestHash);
        }
        return read(binding.planScope(), id);
    }
    public Evidence read(String scope, String id) {
        return verify(rows("SELECT * FROM evidence_record WHERE plan_scope=? AND id=?", scope, id)
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown evidence in this plan")));
    }
    private Optional<Row> byKey(String scope, String key) {
        return rows("SELECT * FROM evidence_record WHERE plan_scope=? AND operation_key=?", scope, key).stream().findFirst();
    }
    private List<Row> rows(String sql, String... args) {
        return jdbc.query(sql, (rs, n) -> new Row(rs.getString("id"), rs.getString("plan_scope"),
                rs.getString("operation_key"), rs.getString("request_hash"), rs.getString("payload")), (Object[]) args);
    }
    private Evidence replay(Row row, String requestHash) {
        Evidence evidence = verify(row);
        if (!row.requestHash().equals(requestHash)) throw new IllegalStateException("Evidence key belongs to a different binding, author or content");
        return evidence;
    }
    private Evidence verify(Row row) {
        if (!row.id().equals(hash(row.payload()))) throw new IllegalStateException("Evidence content integrity check failed");
        try {
            Document document = mapper.readValue(row.payload(), Document.class);
            if (!row.scope().equals(document.binding().planScope()) || !row.key().equals(document.operationKey())
                    || !row.requestHash().equals(requestHash(document.binding(), document.kind(), document.author(), document.content())))
                throw new IllegalStateException("Evidence metadata integrity check failed");
            return new Evidence(row.id(), document.operationKey(), document.binding(), document.kind(), document.author(), document.createdAt(), document.content());
        } catch (java.io.IOException ex) { throw new IllegalStateException("Cannot decode evidence", ex); }
    }
    private String requestHash(Binding binding, String kind, String author, JsonNode content) {
        return hash(json(canonical(mapper.valueToTree(Map.of("binding", binding, "kind", kind, "author", author, "content", content)), 0)));
    }
    private JsonNode canonical(JsonNode value, int depth) {
        if (depth > 32) throw new IllegalArgumentException("Evidence nesting exceeds limit");
        if (value.isObject()) {
            ObjectNode result = mapper.createObjectNode(); var names = new TreeSet<String>(); value.fieldNames().forEachRemaining(names::add);
            for (String name : names) result.set(name, canonical(value.get(name), depth + 1));
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = mapper.createArrayNode(); for (JsonNode item : value) result.add(canonical(item, depth + 1)); return result;
        }
        return value.deepCopy();
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (java.io.IOException ex) { throw new IllegalArgumentException("Cannot encode evidence", ex); }
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private static boolean text(String value, int max) { return value != null && !value.isBlank() && value.length() <= max; }
}
