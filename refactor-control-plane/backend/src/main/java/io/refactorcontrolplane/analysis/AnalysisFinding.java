package io.refactorcontrolplane.analysis;

import java.util.List;

public record AnalysisFinding(String kind, String category, String statement, List<String> evidenceIds) {
    public AnalysisFinding { evidenceIds = List.copyOf(evidenceIds); }
}
