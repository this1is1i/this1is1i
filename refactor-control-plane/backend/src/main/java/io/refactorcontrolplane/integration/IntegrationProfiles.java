package io.refactorcontrolplane.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class IntegrationProfiles {
    private final String file;
    private final ObjectMapper mapper;
    public IntegrationProfiles(@Value("${control-plane.integration-checks-file:}") String file, ObjectMapper mapper) {
        this.file = file; this.mapper = mapper;
    }
    public record Check(String id, List<String> argv, String workingDirectory, int timeoutSeconds) {
        public Check {
            if (id == null || id.isBlank() || argv == null || argv.isEmpty() || argv.size() > 100
                    || argv.stream().anyMatch(arg -> arg == null || arg.indexOf('\0') >= 0)
                    || argv.getFirst().isBlank() || workingDirectory == null
                    || Path.of(workingDirectory).isAbsolute() || Path.of(workingDirectory).normalize().startsWith("..")
                    || timeoutSeconds < 1 || timeoutSeconds > 300) throw new IllegalArgumentException("Invalid configured check");
            argv = List.copyOf(argv);
        }
    }
    public record Profile(String id, String version, List<Check> checks) {
        public Profile {
            if (id == null || id.isBlank() || version == null || version.isBlank() || checks == null
                    || checks.isEmpty() || checks.size() > 10
                    || checks.stream().map(Check::id).distinct().count() != checks.size())
                throw new IllegalArgumentException("Invalid validation profile");
            checks = List.copyOf(checks);
        }
    }
    public Profile load(String id) {
        if (file.isBlank()) throw new IllegalStateException("Local validation is disabled; configure a project-owned integration-checks-file");
        return available().stream().filter(profile -> profile.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown validation profile"));
    }
    public List<Profile> available() {
        if (file.isBlank()) return List.of();
        try {
            Path path = Path.of(file);
            if (Files.size(path) > 65536) throw new IllegalStateException("Validation configuration exceeds 64 KiB");
            Profile[] profiles = mapper.readValue(Files.readString(path, java.nio.charset.StandardCharsets.UTF_8), Profile[].class);
            if (Arrays.stream(profiles).map(Profile::id).distinct().count() != profiles.length)
                throw new IllegalArgumentException("Duplicate validation profile");
            return List.of(profiles);
        } catch (java.io.IOException ex) { throw new IllegalStateException("Cannot load validation configuration", ex); }
    }
}
