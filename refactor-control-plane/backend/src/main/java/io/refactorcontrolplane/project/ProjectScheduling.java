package io.refactorcontrolplane.project;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** One slot per claimed execution and per declared environment. Part of the approved plan. */
public record ProjectScheduling(Map<String, Integer> actorCapacities, Map<String, Integer> environmentCapacities) {
    public ProjectScheduling {
        actorCapacities = actorCapacities == null ? null : Map.copyOf(actorCapacities);
        environmentCapacities = environmentCapacities == null ? null : Map.copyOf(environmentCapacities);
    }

    // Check JSON before Jackson can coerce a fraction, numeric string or boolean to an integer.
    static void validateJson(JsonNode definition) {
        JsonNode policy = definition.path("scheduling");
        if (policy.isMissingNode() || policy.isNull()) return;
        if (!policy.isObject()) throw new IllegalArgumentException("scheduling must be an object");
        for (String field : Set.of("actorCapacities", "environmentCapacities")) {
            JsonNode values = policy.path(field);
            if (!values.isObject() || values.size() > 1000) throw new IllegalArgumentException(field + " must be a bounded capacity map");
            values.properties().forEach(entry -> {
                var value = entry.getValue();
                if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1 || value.intValue() > 1000)
                    throw new IllegalArgumentException("Capacity must be an integer from 1 to 1000: " + entry.getKey());
            });
        }
    }

    void validate(Map<String, ProjectDefinition.Resource> resources) {
        validateMap(actorCapacities); validateMap(environmentCapacities);
        if (actorCapacities.isEmpty()) throw new IllegalArgumentException("Declare at least one execution actor capacity");
        Set<String> environments = resources.entrySet().stream().filter(entry -> entry.getValue().kind().equals("ENVIRONMENT"))
                .map(Map.Entry::getKey).collect(Collectors.toSet());
        if (!environments.equals(environmentCapacities.keySet()))
            throw new IllegalArgumentException("Environment capacities must cover exactly the ENVIRONMENT resources");
    }

    private static void validateMap(Map<String, Integer> values) {
        if (values == null || values.size() > 1000) throw new IllegalArgumentException("Capacity maps are required");
        values.forEach((id, capacity) -> {
            if (id == null || id.isBlank() || !id.equals(id.strip()) || id.length() > 100 || id.chars().anyMatch(c -> c < 32)
                    || capacity == null || capacity < 1 || capacity > 1000)
                throw new IllegalArgumentException("Invalid capacity declaration");
        });
    }
}
