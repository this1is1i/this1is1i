package io.refactorcontrolplane.project;

import io.refactorcontrolplane.api.DemoProjectOverview;
import io.refactorcontrolplane.integration.IntegrationProfiles;
import java.nio.file.Path;
import java.util.*;

/** The governance runtime consumes project input, never a sample plan factory. */
public interface ProjectSource {
    Path repository();
    DemoProjectOverview overview();
    default Set<String> forbiddenPaths() { return Set.of(".git", "backend/data"); }
    default boolean configured() { return false; }
    default String planScope() { return ""; }
    default boolean approved() { return true; }
    default void requireExecutable() {}
    default List<IntegrationProfiles.Profile> validationProfiles() { return List.of(); }
    default void requireValidationProfile(List<String> taskIds, IntegrationProfiles.Profile selected) {}
    default String targetRef(String fallback) { return fallback; }
    default ProjectScheduling scheduling() { return null; }
    default Set<String> taskEnvironments(String taskId) { return Set.of(); }
    default List<IntegrationProfiles.Check> executionChecks(String taskId) { throw new IllegalStateException("Approved task validation is required"); }
}
