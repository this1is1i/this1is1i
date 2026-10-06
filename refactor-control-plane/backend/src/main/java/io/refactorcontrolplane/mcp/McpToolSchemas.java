package io.refactorcontrolplane.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** The deliberately small JSON Schema subset used by this server's tool contracts. */
public final class McpToolSchemas {
    private static final Map<String, Object> TEXT = Map.of("type", "string");
    private static final Map<String, Object> NONBLANK = Map.of("type", "string", "pattern", "^[\\s\\S]*\\S[\\s\\S]*$");
    private static final Map<String, Object> POSITIVE = Map.of("type", "integer", "minimum", 1);
    private static final Map<String, Object> TTL = Map.of("type", "integer", "minimum", 1, "maximum", 86400, "default", 300);
    private static final Map<String, Object> BOOL = Map.of("type", "boolean");
    private static final Map<String, Object> STRINGS = array(TEXT);
    private McpToolSchemas() {}

    public static Map<String, Object> input(String tool) {
        var fields = new LinkedHashMap<String, Object>();
        var required = new ArrayList<String>();
        if (!tool.startsWith("get_")) {
            fields.put("taskId", NONBLANK); fields.put("taskToken", NONBLANK);
            fields.put("idempotencyKey", Map.of("type", "string", "minLength", 1, "maxLength", 200, "pattern", "^[\\s\\S]*\\S[\\s\\S]*$"));
            fields.put("expectedVersion", POSITIVE);
            required.addAll(fields.keySet());
        }
        switch (tool) {
            case "get_task_package" -> { fields.put("taskId", NONBLANK); required.add("taskId"); }
            case "reserve_paths" -> {
                fields.put("ownerId", NONBLANK); fields.put("resourcePath", NONBLANK);
                fields.put("mode", enumeration("READ", "SHARED_WRITE", "EXCLUSIVE_WRITE"));
                fields.put("ttlSeconds", TTL); fields.put("taskExecutionId", NONBLANK);
                required.addAll(List.of("ownerId", "resourcePath"));
            }
            case "heartbeat", "release_paths" -> {
                fields.put("leaseId", Map.of("type", "string", "pattern", "^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$"));
                fields.put("leaseVersion", POSITIVE); fields.put("taskExecutionId", NONBLANK);
                required.addAll(List.of("leaseId", "leaseVersion"));
                if (tool.equals("heartbeat")) fields.put("ttlSeconds", TTL);
            }
            case "publish_contract_change", "publish_schema_change", "record_architecture_decision" -> {
                fields.put("assetVersion", POSITIVE); fields.put("affectedTaskIds", array(NONBLANK));
            }
            case "submit_changeset" -> {
                fields.put("commitSha", NONBLANK); required.add("commitSha");
                fields.put("changedPaths", Map.of("type", "array", "items", TEXT, "deprecated", true,
                        "description", "Ignored; authorization uses the verified Git diff."));
            }
            case "report_change_event" -> {
                fields.put("type", NONBLANK); required.add("type");
                fields.put("targetStatus", TEXT);
                // Accepted only so attempted client-side approvals still receive an explicit authorization error.
                fields.put("role", TEXT); fields.put("gate", TEXT); fields.put("passed", BOOL);
            }
            default -> { }
        }
        return object(fields, required);
    }

    public static Map<String, Object> output(String tool) {
        var task = task();
        return switch (tool) {
            case "get_project_context" -> object(Map.of("projectId", TEXT, "analysis", analysis(), "plan", plan(),
                    "mergePolicy", TEXT, "dataBoundary", TEXT));
            case "get_ready_tasks" -> object(Map.of("tasks", array(task), "contextVersion", POSITIVE));
            case "get_task_package" -> task;
            case "get_dependency_impact" -> object(Map.of("criticalPath", STRINGS));
            case "get_integration_status" -> object(Map.of("contextVersion", POSITIVE, "activeLeases", array(lease()),
                    "events", array(object(Map.of("sequence", POSITIVE, "idempotencyKey", TEXT, "type", TEXT,
                            "subject", TEXT, "occurredAt", TEXT, "details", dictionary(TEXT)))),
                    "reconciliationRequired", BOOL, "automaticallyMergeMain", BOOL, "mergePolicy", TEXT));
            case "claim_task" -> object(Map.of("task", task, "contextVersion", POSITIVE));
            case "reserve_paths" -> object(Map.of("accepted", BOOL, "lease", nullable(lease()), "conflicts",
                    array(object(Map.of("leaseId", TEXT, "taskExecutionId", TEXT, "resourcePath", TEXT,
                            "mode", TEXT, "reason", TEXT)))));
            case "heartbeat" -> lease();
            case "release_paths" -> object(Map.of("released", BOOL));
            case "publish_contract_change", "publish_schema_change", "record_architecture_decision" ->
                    object(Map.of("published", BOOL, "affectedTaskIds", STRINGS, "contextVersion", POSITIVE));
            case "submit_changeset" -> object(Map.of("accepted", BOOL, "unauthorizedPaths", STRINGS));
            case "report_blocker" -> object(Map.of("blocked", BOOL, "task", task));
            case "report_change_event" -> object(Map.of("recorded", BOOL, "task", task, "taskId", TEXT,
                    "type", TEXT, "contextVersion", POSITIVE), List.of("recorded", "type", "contextVersion"));
            default -> throw new IllegalArgumentException("Unknown tool " + tool);
        };
    }

    private static Map<String, Object> task() {
        var fields = new LinkedHashMap<String, Object>();
        for (String key : List.of("id", "baseCommit", "workingDirectory", "status")) fields.put(key, TEXT);
        for (String key : List.of("contextVersion", "contractVersion", "schemaVersion", "adrVersion")) fields.put(key, POSITIVE);
        for (String key : List.of("allowedPaths", "forbiddenPaths", "acceptanceCommands")) fields.put(key, STRINGS);
        fields.put("currentlyValid", BOOL);
        var required = List.copyOf(fields.keySet());
        fields.put("execution", object(Map.of("taskId", TEXT, "executionId", TEXT, "branch", TEXT, "path", TEXT,
                "baseCommit", TEXT, "commitSha", nullable(TEXT))));
        return object(fields, required);
    }

    private static Map<String, Object> lease() {
        var fields = new LinkedHashMap<String, Object>();
        for (String key : List.of("leaseId", "ownerId", "taskExecutionId", "resourcePath", "mode",
                "startedAt", "heartbeatAt", "expiresAt")) fields.put(key, TEXT);
        fields.put("version", POSITIVE);
        return object(fields);
    }

    private static Map<String, Object> analysis() {
        return object(Map.of("snapshotCommit", TEXT, "technologyStack", STRINGS,
                "modules", array(object(Map.of("name", TEXT, "path", TEXT, "fileCount", Map.of("type", "integer", "minimum", 0)))),
                "evidence", array(object(Map.of("claim", TEXT, "path", TEXT, "sourceType", TEXT, "id", TEXT,
                        "snapshotCommit", TEXT, "blobId", nullable(TEXT)))),
                "currentArchitectureMermaid", TEXT, "risks", STRINGS, "upgradePoints", STRINGS,
                "findings", array(object(Map.of("kind", enumeration("FACT", "INFERENCE", "UNKNOWN"),
                        "category", TEXT, "statement", TEXT, "evidenceIds", STRINGS)))));
    }

    private static Map<String, Object> plan() {
        return object(Map.of("baseCommit", TEXT, "targetArchitectureMermaid", TEXT,
                "workPackages", array(object(Map.of("id", TEXT, "title", TEXT, "role", TEXT, "dependencies", STRINGS,
                        "allowedPaths", STRINGS, "estimatedHours", POSITIVE, "workingDirectory", TEXT, "acceptanceCommands", STRINGS))),
                "criticalPath", STRINGS, "parallelExecutionGroups", dictionary(STRINGS), "apiContract", TEXT,
                "databaseCompatibility", TEXT, "functionalParity", dictionary(TEXT), "requiredEvidence", STRINGS));
    }

    private static Map<String, Object> dictionary(Object values) { return Map.of("type", "object", "additionalProperties", values); }
    private static Map<String, Object> enumeration(String... values) { return Map.of("type", "string", "enum", List.of(values)); }
    private static Map<String, Object> array(Object item) { return Map.of("type", "array", "items", item); }
    private static Map<String, Object> nullable(Map<String, Object> schema) {
        var copy = new LinkedHashMap<>(schema); copy.put("type", List.of(schema.get("type"), "null")); return copy;
    }
    private static Map<String, Object> object(Map<String, Object> fields) { return object(fields, List.copyOf(fields.keySet())); }
    private static Map<String, Object> object(Map<String, Object> fields, List<String> required) {
        return Map.of("type", "object", "properties", fields, "required", required, "additionalProperties", false);
    }

    public static void validateInput(String tool, JsonNode arguments, ObjectMapper mapper) {
        validate(mapper.valueToTree(input(tool)), arguments, "arguments");
        if (tool.equals("report_change_event") && arguments.path("type").asText().equals("TaskStatusChanged")
                && (!arguments.path("targetStatus").isTextual() || arguments.path("targetStatus").asText().isBlank()))
            throw new IllegalArgumentException("arguments.targetStatus is required for TaskStatusChanged");
    }

    public static void validate(JsonNode schema, JsonNode value, String path) {
        if (value == null || value.isMissingNode()) throw new IllegalArgumentException(path + " is required");
        JsonNode type = schema.path("type");
        if (type.isArray()) {
            if (value.isNull()) return;
            type = type.get(0);
        }
        boolean valid = switch (type.asText()) {
            case "object" -> value.isObject(); case "array" -> value.isArray(); case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber() && value.canConvertToLong(); case "boolean" -> value.isBoolean();
            default -> throw new IllegalArgumentException("Unsupported schema type at " + path);
        };
        if (!valid) throw new IllegalArgumentException(path + " must be " + type.asText());
        if (value.isObject()) {
            schema.path("required").forEach(field -> { if (!value.has(field.asText())) throw new IllegalArgumentException(path + "." + field.asText() + " is required"); });
            value.properties().forEach(field -> {
                JsonNode property = schema.path("properties").get(field.getKey());
                if (property != null) validate(property, field.getValue(), path + "." + field.getKey());
                else if (schema.path("additionalProperties").isObject()) validate(schema.get("additionalProperties"), field.getValue(), path + "." + field.getKey());
                else if (!schema.path("additionalProperties").asBoolean(true)) throw new IllegalArgumentException(path + "." + field.getKey() + " is not allowed");
            });
        } else if (value.isArray()) {
            for (int i = 0; i < value.size(); i++) validate(schema.path("items"), value.get(i), path + "[" + i + "]");
        } else if (value.isTextual()) {
            String text = value.textValue();
            int length = text.codePointCount(0, text.length());
            if (length < schema.path("minLength").asInt(0) || length > schema.path("maxLength").asInt(Integer.MAX_VALUE)
                    || (schema.has("pattern") && !text.matches(schema.path("pattern").asText())))
                throw new IllegalArgumentException(path + " has invalid text");
        } else if (value.isIntegralNumber() && (value.longValue() < schema.path("minimum").asLong(Long.MIN_VALUE)
                || value.longValue() > schema.path("maximum").asLong(Long.MAX_VALUE))) {
            throw new IllegalArgumentException(path + " is outside allowed range");
        }
        if (schema.has("enum")) {
            boolean found = false;
            for (JsonNode option : schema.get("enum")) found |= option.equals(value);
            if (!found) throw new IllegalArgumentException(path + " is not an allowed value");
        }
    }
}
