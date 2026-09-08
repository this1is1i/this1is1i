package io.refactorcontrolplane.governance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.refactorcontrolplane.ownership.RepositoryPathPolicy;
import io.refactorcontrolplane.workpackage.WorkPackageStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GovernedProjectContractTest {

    private GovernedProject project;

    @BeforeEach
    void setUp() {
        project = new GovernedProject(
                "legacy-blog",
                Clock.fixed(Instant.parse("2026-09-08T00:00:00Z"), ZoneOffset.UTC),
                RepositoryPathPolicy.caseInsensitive());
        project.registerTask(new TaskPackage(
                "WP-API",
                "abc123",
                1,
                1,
                1,
                1,
                Set.of("backend/src/main"),
                Set.of("backend/src/main/resources/application-prod.yml"),
                "backend",
                List.of("mvn verify"),
                WorkPackageStatus.RUNNING));
    }

    @Test
    void breakingContractChangeInvalidatesDependentTaskAndIsIdempotent() {
        project.publishSemanticChange(
                SemanticAsset.API_CONTRACT, 2, Set.of("WP-API"), "contract-v2");
        project.publishSemanticChange(
                SemanticAsset.API_CONTRACT, 2, Set.of("WP-API"), "contract-v2");

        assertEquals(WorkPackageStatus.STALE, project.task("WP-API").status());
        assertEquals(2, project.contextVersion());
        assertEquals(3, project.events().size());
        assertEquals(List.of("TaskCreated", "ContractPublished", "TaskInvalidated"),
                project.events().stream().map(ChangeEvent::type).toList());
    }

    @Test
    void changesOutsideAuthorizedScopeAreRejectedAndAudited() {
        SubmissionResult result = project.submitChangeset(
                "WP-API",
                List.of("backend/src/main/ArticleController.java", "frontend/src/App.vue"),
                "submission-1");

        assertFalse(result.accepted());
        assertEquals(List.of("frontend/src/App.vue"), result.unauthorizedPaths());
        assertTrue(project.events().stream().anyMatch(event -> event.type().equals("ChangesetRejected")));
    }

    @Test
    void forbiddenPathOverridesAnAllowedParentPath() {
        SubmissionResult result = project.submitChangeset(
                "WP-API",
                List.of("backend/src/main/resources/application-prod.yml"),
                "submission-prod-config");

        assertFalse(result.accepted());
        assertEquals(List.of("backend/src/main/resources/application-prod.yml"), result.unauthorizedPaths());
    }

    @Test
    void acceptanceRequiresEveryGateAndOnlyProducesAHumanMergeProposal() {
        project.task("WP-API").moveTo(WorkPackageStatus.SUBMITTED);
        project.task("WP-API").moveTo(WorkPackageStatus.CODE_REVIEW);
        project.recordGate("WP-API", QualityGate.CODE_REVIEW, true, ProjectRole.ENGINEERING_LEAD, "gate-1");
        project.task("WP-API").moveTo(WorkPackageStatus.TESTING);
        project.recordGate("WP-API", QualityGate.UNIT_TEST, true, ProjectRole.QA_LEAD, "gate-2");
        project.recordGate("WP-API", QualityGate.INTEGRATION_TEST, true, ProjectRole.QA_LEAD, "gate-3");

        assertThrows(GateNotSatisfiedException.class, () -> project.acceptTask("WP-API"));

        project.recordGate("WP-API", QualityGate.BUSINESS_ACCEPTANCE, true,
                ProjectRole.BUSINESS_APPROVER, "gate-4");
        project.acceptTask("WP-API");
        ManualMergeProposal proposal = project.createManualMergeProposal(
                "WP-API", "codex/task-wp-api", "deadbeef");

        assertEquals(WorkPackageStatus.ACCEPTED, project.task("WP-API").status());
        assertFalse(proposal.automaticallyMergeMain());
        assertTrue(proposal.humanApprovalRequired());
    }

    @Test
    void gateEnforcesTheApprovingRole() {
        assertThrows(UnauthorizedApprovalException.class, () -> project.recordGate(
                "WP-API", QualityGate.BUSINESS_ACCEPTANCE, true,
                ProjectRole.DEVELOPER, "invalid-approval"));
    }

    @Test
    void semanticInvalidationClearsAllEarlierQualityGates() {
        project.task("WP-API").moveTo(WorkPackageStatus.SUBMITTED);
        project.task("WP-API").moveTo(WorkPackageStatus.CODE_REVIEW);
        project.recordGate("WP-API", QualityGate.CODE_REVIEW, true, ProjectRole.ENGINEERING_LEAD, "old-gate-1");
        project.task("WP-API").moveTo(WorkPackageStatus.TESTING);
        project.recordGate("WP-API", QualityGate.UNIT_TEST, true, ProjectRole.QA_LEAD, "old-gate-2");
        project.recordGate("WP-API", QualityGate.INTEGRATION_TEST, true, ProjectRole.QA_LEAD, "old-gate-3");
        project.recordGate("WP-API", QualityGate.BUSINESS_ACCEPTANCE, true,
                ProjectRole.BUSINESS_APPROVER, "old-gate-4");

        project.publishSemanticChange(SemanticAsset.API_CONTRACT, 2, Set.of("WP-API"), "new-contract");
        project.task("WP-API").moveTo(WorkPackageStatus.READY);
        project.task("WP-API").moveTo(WorkPackageStatus.CLAIMED);
        project.task("WP-API").moveTo(WorkPackageStatus.RUNNING);
        project.task("WP-API").moveTo(WorkPackageStatus.SUBMITTED);
        project.task("WP-API").moveTo(WorkPackageStatus.CODE_REVIEW);
        project.task("WP-API").moveTo(WorkPackageStatus.TESTING);

        assertThrows(GateNotSatisfiedException.class, () -> project.acceptTask("WP-API"));
    }

    @Test
    void auditExportLinksTaskChangesTestsApprovalsAndRisks() {
        project.submitChangeset("WP-API", List.of("frontend/src/App.vue"), "rejected-change");

        AuditReport report = project.auditReport();

        assertEquals("legacy-blog", report.projectId());
        assertEquals(1, report.tasks().size());
        assertTrue(report.eventTypes().contains("ChangesetRejected"));
        assertTrue(report.risks().contains("Unauthorized path modification"));
    }
}
