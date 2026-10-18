package io.refactorcontrolplane.project;

import io.refactorcontrolplane.analysis.MigrationPlan;
import io.refactorcontrolplane.integration.IntegrationProfiles;
import java.util.*;

/** Trusted project-local input. Commands are argv, never inferred from model prose. */
public record ProjectDefinition(String projectId, String repository, String targetRef,
        Set<String> forbiddenPaths, MigrationPlan plan, Map<String, Resource> resources,
        Map<String, Set<String>> taskResources, Map<String, String> directoryCreators,
        Map<String, String> taskProfiles, List<IntegrationProfiles.Profile> validationProfiles,
        String targetCommit, String baselineCandidateId, List<Decision> architectureDecisions,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        ProjectScheduling scheduling) {
    public record Resource(String kind, String locator, String version) {}
    public record EvidenceRef(String path, String blobId) {}
    public record Decision(String id, String statement, List<EvidenceRef> evidence, Set<String> taskIds) {}
    public String pinnedTarget() { return targetCommit == null ? plan.baseCommit() : targetCommit; }
}
