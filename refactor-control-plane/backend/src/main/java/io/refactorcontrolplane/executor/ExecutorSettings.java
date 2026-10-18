package io.refactorcontrolplane.executor;

import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.net.URI;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Administrator-owned allowlist. No endpoint, credential or command is accepted from a task request. */
@Component
public class ExecutorSettings {
    public record Definition(String id, String kind, String baseUrl, String token, Integer timeoutSeconds, Map<String, String> outputs) {}
    private final Map<String, Definition> definitions = new LinkedHashMap<>();
    private final ObjectMapper mapper;
    public ExecutorSettings(@Value("${control-plane.executors.file:}") String file, ObjectMapper mapper) {
        this.mapper = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        if (file.isBlank()) return;
        try {
            Path path = Path.of(file).toAbsolutePath().normalize();
            if (Files.size(path) > 262144) throw new IllegalArgumentException("Executor configuration exceeds 256 KiB");
            var reader = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            var tree = reader.readTree(Files.readString(path, StandardCharsets.UTF_8));
            if (!tree.isArray() || tree.size() > 100) throw new IllegalArgumentException("Supply at most 100 executors");
            for (JsonNode item : tree) {
                if (item.has("timeoutSeconds") && (!item.path("timeoutSeconds").isIntegralNumber()
                        || !item.path("timeoutSeconds").canConvertToInt())) throw new IllegalArgumentException("Executor timeout must be an integer");
                var value = reader.treeToValue(item, Definition.class);
                if (value.id() == null || !value.id().matches("[A-Za-z0-9_-]{1,100}") || !Set.of("MOCK", "HTTP").contains(value.kind())
                        || definitions.containsKey(value.id())) throw new IllegalArgumentException("Invalid executor identity or kind");
                if (value.kind().equals("HTTP")) {
                    URI uri = URI.create(Objects.requireNonNull(value.baseUrl()));
                    boolean local = Set.of("127.0.0.1", "localhost", "[::1]").contains(uri.getHost());
                    if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                            || !(uri.getScheme().equals("https") || (local && uri.getScheme().equals("http")))
                            || value.token() == null || value.token().isBlank() || value.token().contains("\n") || value.token().contains("\r")
                            || value.timeoutSeconds() == null || value.timeoutSeconds() < 1 || value.timeoutSeconds() > 30)
                        throw new IllegalArgumentException("HTTP executor requires a trusted HTTPS endpoint, token and 1-30 second timeout");
                } else if (value.outputs() == null || value.outputs().isEmpty() || value.outputs().size() > 100
                        || value.outputs().values().stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("Mock outputs are required");
                definitions.put(value.id(), value);
            }
        } catch (java.io.IOException ex) { throw new IllegalArgumentException("Cannot load executor configuration"); }
    }
    public Definition get(String id) {
        var value = definitions.get(id); if (value == null) throw new IllegalArgumentException("Unknown configured executor"); return value;
    }
    public List<Map<String, String>> available() {
        return definitions.values().stream().map(value -> Map.of("id", value.id(), "kind", value.kind())).toList();
    }
    public String fingerprint(Definition value) {
        try { return hash(mapper.writeValueAsString(Map.of("id", value.id(), "kind", value.kind(),
                "baseUrl", value.baseUrl() == null ? "" : value.baseUrl(), "outputs", value.outputs() == null ? Map.of() : new TreeMap<>(value.outputs())))); }
        catch (java.io.IOException ex) { throw new IllegalStateException("Cannot fingerprint executor"); }
    }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
