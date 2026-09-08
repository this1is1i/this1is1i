package io.refactorcontrolplane.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LegacyAnalysisAndPlanContractTest {

    @Test
    void analyzesThePinnedUndocumentedLegacyFixtureWithSourceEvidence() {
        Path repository = Path.of("../samples/workspaces/springmvc-blog").toAbsolutePath().normalize();

        LegacyProjectReport report = new LegacyProjectAnalyzer().analyze(repository);

        assertEquals("5e62608af333b5757bf767e05b19e2b0d56f0afb", report.snapshotCommit());
        assertTrue(report.technologyStack().contains("Java 7"));
        assertTrue(report.technologyStack().contains("Spring MVC 4.1"));
        assertTrue(report.technologyStack().contains("JSP"));
        assertTrue(report.modules().stream().anyMatch(module -> module.name().equals("Controllers")));
        assertTrue(report.evidence().stream().allMatch(evidence -> !evidence.path().isBlank()));
        assertTrue(report.currentArchitectureMermaid().contains("flowchart"));
        assertTrue(report.upgradePoints().stream().anyMatch(point -> point.contains("Vue 3")));
    }

    @Test
    void createsAnExecutableArticleMigrationDagAndAcceptanceContract() {
        MigrationPlan plan = ArticleMigrationPlanFactory.create(
                "5e62608af333b5757bf767e05b19e2b0d56f0afb");

        assertTrue(plan.workPackages().size() >= 6);
        assertFalse(plan.criticalPath().isEmpty());
        assertTrue(plan.workPackages().stream().allMatch(task -> !task.acceptanceCommands().isEmpty()));
        assertTrue(plan.workPackages().stream().allMatch(task -> !task.allowedPaths().isEmpty()));
        assertTrue(plan.workPackages().stream().allMatch(task -> !task.workingDirectory().isBlank()));
        assertEquals(3, plan.parallelExecutionGroups().get("implementation").size());
        assertTrue(plan.apiContract().contains("/api/articles"));
        assertTrue(plan.databaseCompatibility().contains("non-destructive"));
        assertTrue(plan.functionalParity().containsKey("文章列表"));
        assertTrue(plan.requiredEvidence().contains("business-acceptance"));
        assertTrue(plan.workPackages().stream()
                .filter(task -> task.id().equals("WP-110"))
                .flatMap(task -> task.allowedPaths().stream())
                .anyMatch("docs/contracts/articles.openapi.yaml"::equals));
        assertTrue(plan.workPackages().stream()
                .filter(task -> task.id().equals("WP-120"))
                .flatMap(task -> task.allowedPaths().stream())
                .anyMatch("backend/src/main/java/io/refactorcontrolplane/article"::equals));
        assertTrue(plan.workPackages().stream()
                .filter(task -> task.id().equals("WP-140"))
                .flatMap(task -> task.acceptanceCommands().stream())
                .noneMatch(command -> command.contains("-Pdb") || command.startsWith("review ")
                        || command.startsWith("validate ") || command.startsWith("verify ")));
    }
}
