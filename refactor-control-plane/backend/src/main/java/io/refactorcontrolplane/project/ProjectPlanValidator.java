package io.refactorcontrolplane.project;

import io.refactorcontrolplane.analysis.*;
import io.refactorcontrolplane.governance.ProjectRole;
import io.refactorcontrolplane.integration.IntegrationProfiles;
import io.refactorcontrolplane.ownership.RepositoryPathPolicy;
import java.nio.file.*;
import java.util.*;

final class ProjectPlanValidator {
    private static final RepositoryPathPolicy PATHS = RepositoryPathPolicy.caseInsensitive();

    static void validate(ProjectDefinition input, Path repository) {
        if (input == null || input.plan() == null) fail("Plan is required");
        if (input.projectId() == null || !input.projectId().matches("[A-Za-z0-9][A-Za-z0-9_-]{0,99}")) fail("Invalid project ID");
        if (!Files.isDirectory(repository.resolve(".git"))) fail("Use the primary Git repository root");
        if (input.targetRef() == null || !input.targetRef().matches("refs/heads/[A-Za-z0-9][A-Za-z0-9/_-]*")) fail("Invalid target branch ref");
        try {
            if (!GitAnalysisSnapshot.localRefCommit(repository, input.targetRef()).equals(input.pinnedTarget()))
                fail("Target branch must match the plan baseline");
        } catch (IllegalStateException ex) { throw new IllegalArgumentException("Target branch cannot be resolved", ex); }
        if (input.forbiddenPaths() == null || !input.forbiddenPaths().contains(".git")) fail("Protect .git explicitly");
        input.forbiddenPaths().forEach(ProjectPlanValidator::path);
        if (input.plan() == null || input.resources() == null || input.taskResources() == null
                || input.taskProfiles() == null || input.directoryCreators() == null || input.validationProfiles() == null)
            fail("Plan, resources, task mappings and validation profiles are required");
        var plan = input.plan();
        if (plan.workPackages().isEmpty() || plan.workPackages().size() > 100) fail("Supply 1 to 100 work packages");
        if (plan.targetArchitectureMermaid() == null || plan.targetArchitectureMermaid().isBlank()
                || plan.apiContract() == null || plan.apiContract().isBlank() || plan.databaseCompatibility() == null
                || plan.databaseCompatibility().isBlank()) fail("Target architecture and compatibility decisions are required");
        Map<String, PlanWorkPackage> tasks = new LinkedHashMap<>();
        for (var task : plan.workPackages()) {
            if (task.id() == null || !task.id().matches("[A-Za-z0-9][A-Za-z0-9_-]{0,99}") || tasks.put(task.id(), task) != null)
                fail("Invalid or duplicate task ID");
            if (task.title() == null || task.title().isBlank() || task.estimatedHours() < 1) fail("Task title and positive estimate required");
            try { ProjectRole.valueOf(task.role()); } catch (RuntimeException ex) { fail("Unknown task role"); }
            path(task.workingDirectory());
            if (task.allowedPaths().isEmpty()) fail("Allowed paths are required");
            for (String allowed : task.allowedPaths()) {
                path(allowed);
                if (input.forbiddenPaths().stream().anyMatch(forbidden -> PATHS.covers(forbidden, allowed))) fail("Task path is forbidden");
            }
        }
        var closure = new HashMap<String, Set<String>>();
        for (String id : tasks.keySet()) ancestors(id, tasks, closure, new HashSet<>());
        if (!tasks.keySet().equals(input.taskResources().keySet()) || !tasks.keySet().equals(input.taskProfiles().keySet()))
            fail("Resource and profile mappings must cover exactly the declared tasks");
        if (!tasks.keySet().containsAll(plan.criticalPath()) || plan.parallelExecutionGroups().values().stream().anyMatch(ids -> !tasks.keySet().containsAll(ids)))
            fail("Plan grouping refers to an unknown task");
        var profiles = new HashMap<String, IntegrationProfiles.Profile>();
        for (var profile : input.validationProfiles()) {
            if (profiles.put(profile.id(), profile) != null) fail("Duplicate validation profile");
            profile.checks().forEach(check -> path(check.workingDirectory()));
        }
        for (var resource : input.resources().entrySet()) {
            var value = resource.getValue();
            if (resource.getKey().isBlank() || value == null || value.kind() == null
                    || !Set.of("PATH", "API_CONTRACT", "DB_SCHEMA", "ADR", "ENVIRONMENT").contains(value.kind())
                    || value.locator() == null || value.locator().isBlank() || value.version() == null || value.version().isBlank())
                fail("Invalid resource declaration");
            if (value.kind().equals("PATH")) path(value.locator());
        }
        if (input.scheduling() != null) input.scheduling().validate(input.resources());
        Set<String> directories = GitAnalysisSnapshot.directories(repository, plan.baseCommit());
        for (var entry : input.directoryCreators().entrySet()) {
            path(entry.getKey());
            var creator = tasks.get(entry.getValue());
            if (entry.getKey().equals(".") || directories.contains(entry.getKey()) || creator == null
                    || creator.allowedPaths().stream().noneMatch(allowed -> PATHS.covers(allowed, entry.getKey())))
                fail("Directory creation must name a task authorized to create a new directory");
        }
        for (var task : tasks.values()) {
            if (!directories.contains(task.workingDirectory())) {
                fail("Working directory must exist in the fixed baseline; incorporate prerequisite outputs and review a new plan first");
            }
            var profile = profiles.get(input.taskProfiles().get(task.id()));
            if (profile == null || profile.checks().stream().anyMatch(check -> !check.workingDirectory().equals(task.workingDirectory()))
                    || !profile.checks().stream().map(check -> String.join(" ", check.argv())).toList().equals(task.acceptanceCommands()))
                fail("Task commands and working directory must match its trusted validation profile");
            var resources = input.taskResources().get(task.id());
            if (resources == null || resources.isEmpty()) fail("Task resource declarations are required");
            for (String id : resources) {
                var resource = input.resources().get(id);
                if (resource == null) fail("Unknown task resource");
                if (resource.kind().equals("PATH") && task.allowedPaths().stream().noneMatch(allowed -> PATHS.covers(allowed, resource.locator())))
                    fail("Task path resource exceeds its authorization");
            }
        }
        if (input.architectureDecisions() != null) {
            var entries = GitAnalysisSnapshot.capture(repository, plan.baseCommit()).entries();
            var ids = new HashSet<String>();
            for (var decision : input.architectureDecisions()) {
                if (decision.id() == null || decision.id().isBlank() || !ids.add(decision.id())
                        || decision.statement() == null || decision.statement().isBlank() || decision.evidence() == null
                        || decision.evidence().isEmpty() || decision.taskIds() == null || decision.taskIds().isEmpty()
                        || !tasks.keySet().containsAll(decision.taskIds())) fail("ADR needs unique ID, statement, source evidence and declared tasks");
                for (var ref : decision.evidence()) if (entries.stream().noneMatch(entry -> entry.path().equals(ref.path())
                        && entry.blobId() != null && entry.blobId().equals(ref.blobId()))) fail("ADR source evidence does not match the plan Git tree");
            }
        }
    }

    private static Set<String> ancestors(String id, Map<String, PlanWorkPackage> tasks, Map<String, Set<String>> cache, Set<String> visiting) {
        if (cache.containsKey(id)) return cache.get(id);
        if (!tasks.containsKey(id)) fail("Unknown task dependency: " + id);
        if (!visiting.add(id)) fail("Cyclic task dependency");
        var result = new HashSet<String>();
        for (String dependency : tasks.get(id).dependencies()) { result.add(dependency); result.addAll(ancestors(dependency, tasks, cache, visiting)); }
        visiting.remove(id); cache.put(id, Set.copyOf(result)); return result;
    }
    private static void path(String value) {
        if (value == null || value.isBlank() || !value.equals(value.strip()) || value.indexOf('\\') >= 0
                || value.indexOf(':') >= 0 || value.chars().anyMatch(c -> c < 32) || value.startsWith("/")
                || (!value.equals(".") && Arrays.stream(value.split("/", -1)).anyMatch(part -> part.isBlank() || part.equals(".") || part.equals(".."))))
            fail("Use an exact repository-relative path");
    }
    private static void fail(String message) { throw new IllegalArgumentException(message); }
}
