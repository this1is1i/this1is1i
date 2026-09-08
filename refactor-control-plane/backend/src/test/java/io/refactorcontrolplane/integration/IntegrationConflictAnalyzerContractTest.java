package io.refactorcontrolplane.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.refactorcontrolplane.ownership.RepositoryPathPolicy;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class IntegrationConflictAnalyzerContractTest {

    @Test
    void reportsTextAndSemanticConflictsWithAffectedTasksAndSuggestedOrder() {
        IntegrationConflictAnalyzer analyzer = new IntegrationConflictAnalyzer(
                RepositoryPathPolicy.caseInsensitive());

        IntegrationReport report = analyzer.analyze(List.of(
                new ChangesetDescriptor("WP-120", Set.of("backend/src/article"), 2, 1),
                new ChangesetDescriptor("WP-130", Set.of("frontend/src/article"), 1, 1),
                new ChangesetDescriptor("WP-125", Set.of("backend/src/article/Article.java"), 2, 2)));

        assertTrue(report.conflicts().stream().anyMatch(conflict -> conflict.kind().equals("PATH")));
        assertTrue(report.conflicts().stream().anyMatch(conflict -> conflict.kind().equals("API_CONTRACT")));
        assertTrue(report.affectedTasks().containsAll(Set.of("WP-120", "WP-130", "WP-125")));
        assertEquals("WP-120", report.suggestedOrder().getFirst());
        assertFalse(report.automaticallyMergeMain());
    }
}
