package io.refactorcontrolplane.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

final class WebSnapshotDetector implements SnapshotDetector {
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    @Override public void inspect(GitAnalysisSnapshot.Entry entry, AnalysisCollector result) {
        String path = entry.path();
        if (path.equals("package.json") || path.endsWith("/package.json")) {
            result.technology("npm", entry); result.module("npm package", entry);
            try {
                var manifest = mapper.readTree(entry.text());
                if (manifest == null || !manifest.isObject()) throw new IllegalArgumentException("Expected manifest object");
                // Validate the complete declaration before publishing any dependency facts.
                for (String group : List.of("dependencies", "devDependencies", "peerDependencies")) {
                    var dependencies = manifest.path(group);
                    if (!dependencies.isMissingNode() && !dependencies.isObject()) throw new IllegalArgumentException("Expected dependency object");
                    for (var dependency : dependencies.properties()) {
                        if (!dependency.getValue().isTextual()) {
                            throw new IllegalArgumentException("Expected dependency version string");
                        }
                    }
                }
                for (String group : List.of("dependencies", "devDependencies", "peerDependencies")) {
                    var dependencies = manifest.path(group);
                    if (!dependencies.isMissingNode() && !dependencies.isObject()) throw new IllegalArgumentException("Expected dependency object");
                    dependencies.properties().forEach(dependency -> {
                        result.finding("FACT", "dependency", "Declared " + group + " " + dependency.getKey()
                                + ": " + dependency.getValue().asText(), entry);
                        switch (dependency.getKey()) {
                            case "vue" -> result.technology("Vue", entry);
                            case "react" -> result.technology("React", entry);
                            case "vite" -> result.technology("Vite", entry);
                            case "jquery" -> result.technology("jQuery", entry);
                            default -> { }
                        }
                    });
                }
                result.finding("UNKNOWN", "dependency", "Package ranges are declarations, not resolved installed versions", entry);
            } catch (Exception exception) {
                result.finding("UNKNOWN", "manifest", "npm manifest is malformed or unsupported", entry);
            }
        } else if (path.endsWith(".jsp")) {
            result.technology("JSP", entry); result.module("JSP Views", entry);
            result.upgrade("Evaluate JSP → Vue 3 for the approved UI migration; preserve business behavior", entry);
        } else if (path.endsWith(".java")) {
            result.technology("Java", entry);
            String source = entry.text().replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//[^\\r\\n]*", "");
            if (source.matches("(?s).*@(?:RestController|Controller)\\b.*")) {
                result.module("Controllers", entry);
                result.finding("INFERENCE", "module", "Controller annotation suggests a request handling component", entry);
            }
            if (source.matches("(?s).*@Repository\\b.*")) {
                result.module("Data Access", entry);
                result.finding("INFERENCE", "module", "Repository annotation suggests a persistence component", entry);
            }
        } else if (path.endsWith(".sql")) {
            result.module("SQL scripts", entry); result.finding("FACT", "source", "SQL source file is present", entry);
        } else if (path.endsWith(".py")) result.technology("Python source", entry);
        else if (path.endsWith(".ts")) result.technology("TypeScript source", entry);
        else if (path.endsWith(".js")) {
            result.technology("JavaScript source", entry);
            if (entry.text().matches("(?s).*jQuery(?: JavaScript Library)? v?\\d+\\.\\d+.*")) result.technology("jQuery", entry);
        } else if (path.endsWith(".vue")) result.technology("Vue single-file component", entry);
        else if (path.endsWith(".gradle") || path.endsWith(".gradle.kts")) {
            result.finding("UNKNOWN", "coverage", "Gradle configuration detected; dynamic build evaluation is unsupported", entry);
        }
    }
}
