package io.refactorcontrolplane.analysis;

import java.util.List;

public record ArchitectureRelation(String kind, String from, String to, String confidence,
        String explanation, List<String> evidenceIds) {
    public ArchitectureRelation { evidenceIds = List.copyOf(evidenceIds); }
}
