package io.refactorcontrolplane.analysis;

import java.nio.file.Path;
import java.util.List;

public final class LegacyProjectAnalyzer {
    private final List<SnapshotDetector> detectors = List.of(new MavenSnapshotDetector(), new WebSnapshotDetector());

    public LegacyProjectReport analyze(Path repository) {
        return analyze(GitAnalysisSnapshot.capture(repository));
    }

    public LegacyProjectReport analyze(GitAnalysisSnapshot snapshot) {
        var result = new AnalysisCollector(snapshot);
        for (var entry : snapshot.entries()) {
            if (entry.limitation() != null) {
                result.finding("UNKNOWN", "coverage", entry.limitation(), entry);
            } else {
                for (var detector : detectors) detector.inspect(entry, result);
            }
        }
        new RelationshipDetector().inspect(snapshot, result);
        if (result.stack.isEmpty()) result.finding("UNKNOWN", "technology", "No supported technology declaration detected", null);
        result.finding("UNKNOWN", "architecture", "Runtime versions, call relationships and deployment topology require additional evidence", null);
        return result.report();
    }
}
