package io.refactorcontrolplane.project;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.refactorcontrolplane.analysis.*;
import io.refactorcontrolplane.api.DemoProjectOverview;
import io.refactorcontrolplane.governance.LocalIdentityRegistry.Principal;
import io.refactorcontrolplane.governance.ProjectRole;
import io.refactorcontrolplane.integration.IntegrationProfiles;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@DependsOnDatabaseInitialization
@ConditionalOnProperty(name="control-plane.project.mode", havingValue="configured")
public class ConfiguredProjectService implements ProjectSource {
    private final JdbcTemplate jdbc;
    private final ProjectDefinition definition;
    private final DemoProjectOverview overview;
    private final Path repository;
    private final String fingerprint;
    private final String json;
    final ObjectMapper reader;
    final TransactionTemplate transactions;

    public ConfiguredProjectService(@Value("${control-plane.project.definition-file:}") String file,
            JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
        try {
            if (file.isBlank()) throw new IllegalArgumentException("A project definition file is required");
            Path path = Path.of(file).toAbsolutePath().normalize();
            if (Files.size(path) > 262144) throw new IllegalArgumentException("Project definition exceeds 256 KiB");
            reader = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature());
            reader.getFactory().setStreamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder().maxNestingDepth(40).build());
            JsonNode input = reader.readTree(Files.readString(path, StandardCharsets.UTF_8));
            if (!input.isObject() || !input.path("repository").isTextual()) throw new IllegalArgumentException("Project repository is required");
            repository = path.getParent().resolve(input.path("repository").asText()).toRealPath();
            ((ObjectNode) input).put("repository", repository.toString());
            String supplied = reader.writeValueAsString(canonical(input, reader));
            String suppliedFingerprint = digest(supplied);
            var active = stored();
            if (active.isPresent() && !suppliedFingerprint.equals(active.get().get("fingerprint"))) {
                var known = jdbc.queryForList("SELECT definition_json FROM project_revision WHERE fingerprint=? AND activated_at IS NOT NULL", String.class, suppliedFingerprint);
                if (known.size() != 1 || !known.getFirst().equals(supplied))
                    throw new IllegalStateException("Database is bound to another immutable project plan; stage a revision explicitly");
                input = reader.readTree(active.get().get("definition_json"));
                if (!repository.toString().equals(input.path("repository").asText())) throw new IllegalStateException("Persisted repository changed");
            }
            ProjectScheduling.validateJson(input);
            definition = reader.treeToValue(input, ProjectDefinition.class);
            if (definition.plan() == null) throw new IllegalArgumentException("Plan is required");
            var snapshot = GitAnalysisSnapshot.capture(repository, definition.plan().baseCommit());
            if (!GitAnalysisSnapshot.headCommit(repository).equals(definition.pinnedTarget())) throw new IllegalStateException("Repository baseline changed; revalidate the plan");
            if (active.isEmpty() && !definition.plan().baseCommit().equals(definition.pinnedTarget()))
                throw new IllegalArgumentException("Initial plan must start at the target; incorporate candidates through a reviewed revision");
            ProjectPlanValidator.validate(definition, repository);
            json = reader.writeValueAsString(canonical(input, reader));
            fingerprint = digest(json);
            overview = new DemoProjectOverview(definition.projectId(), new LegacyProjectAnalyzer().analyze(snapshot),
                    definition.plan(), "HUMAN_ONLY", "SOURCE_CODE_STAYS_INTERNAL");
            bind();
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("Cannot load project definition", ex);
        }
    }

    private void bind() {
        transactions.executeWithoutResult(status -> bindTransaction());
    }
    private void bindTransaction() {
        var existing = stored();
        if (existing.isEmpty()) {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Long.class) != 0)
                throw new IllegalStateException("Unbound operation journal exists; use a separate project database");
            try { jdbc.update("INSERT INTO project_plan (slot, fingerprint, definition_json) VALUES (1, ?, ?)", fingerprint, json); }
            catch (DuplicateKeyException race) { /* Read the winner, never overwrite it. */ }
        }
        var row = stored().orElseThrow();
        if (!fingerprint.equals(row.get("fingerprint")) || !json.equals(row.get("definition_json")))
            throw new IllegalStateException("Database is bound to another immutable project plan; explicit migration is required");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM project_revision", Long.class) == 0) {
            jdbc.update("INSERT INTO project_revision(fingerprint,definition_json,architect,architect_at,manager,manager_at,activated_at) SELECT fingerprint,definition_json,architect,architect_at,manager,manager_at,CURRENT_TIMESTAMP FROM project_plan WHERE slot=1");
            jdbc.update("UPDATE audit_event SET plan_scope=? WHERE plan_scope=''", fingerprint);
            jdbc.update("UPDATE integration_record SET plan_scope=? WHERE plan_scope=''",fingerprint);
        } else if (jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE plan_scope=''", Long.class) > 0)
            throw new IllegalStateException("Unscoped journal rows require reconciliation");
    }

    private Optional<Map<String, String>> stored() {
        return jdbc.query("SELECT fingerprint, definition_json, architect, manager FROM project_plan WHERE slot=1", (rs, n) -> {
            Map<String, String> value = new HashMap<>();
            for (String key : List.of("fingerprint", "definition_json", "architect", "manager")) value.put(key, rs.getString(key));
            return value;
        }).stream().findFirst();
    }

    public Map<String, Object> state(Principal actor) {
        authorize(actor, null);
        var row = checkedRow();
        var result = new LinkedHashMap<String, Object>();
        result.put("fingerprint", fingerprint); result.put("status", approved(row) ? "APPROVED" : "DRAFT");
        result.put("definition", definition); result.put("analysis", overview.analysis());
        result.put("architect", row.get("architect")); result.put("manager", row.get("manager"));
        return result;
    }

    public synchronized Map<String, Object> decide(Principal actor, String expectedFingerprint, String decision) {
        String column;
        if ("ARCHITECTURE".equals(decision)) { authorize(actor, ProjectRole.ARCHITECT); column = "architect"; }
        else if ("EXECUTION".equals(decision)) { authorize(actor, ProjectRole.PROJECT_MANAGER); column = "manager"; }
        else throw new IllegalArgumentException("Decision must be ARCHITECTURE or EXECUTION");
        var row = checkedRow();
        if (!fingerprint.equals(expectedFingerprint)) throw new IllegalStateException("Plan fingerprint changed");
        requireBaseline();
        if (column.equals("manager") && row.get("architect") == null) throw new IllegalStateException("Architecture confirmation is required first");
        String other = row.get(column.equals("manager") ? "architect" : "manager");
        if (actor.subjectId().equals(other)) throw new SecurityException("Architecture and execution require independent subjects");
        if (row.get(column) != null) {
            if (!actor.subjectId().equals(row.get(column))) throw new IllegalStateException("Decision already recorded by another subject");
            return state(actor);
        }
        // Column names come exclusively from the enum-like branch above, not request text.
        transactions.executeWithoutResult(status -> {
            int updated = jdbc.update("UPDATE project_plan SET " + column + "=?, " + column + "_at=CURRENT_TIMESTAMP WHERE slot=1 AND fingerprint=? AND " + column + " IS NULL",
                    actor.subjectId(), fingerprint);
            if (updated != 1) throw new IllegalStateException("Concurrent plan decision; read current state");
            jdbc.update("UPDATE project_revision SET " + column + "=?, " + column + "_at=CURRENT_TIMESTAMP WHERE fingerprint=?",
                    actor.subjectId(), fingerprint);
        });
        return state(actor);
    }

    void authorize(Principal actor, ProjectRole role) {
        if (!definition.projectId().equals(actor.projectId()) || (role != null && !actor.roles().contains(role)))
            throw new SecurityException("Project membership and required decision role are required");
    }
    private Map<String, String> checkedRow() {
        var row = stored().orElseThrow(() -> new IllegalStateException("Project plan is missing"));
        if (!fingerprint.equals(row.get("fingerprint")) || !json.equals(row.get("definition_json")))
            throw new IllegalStateException("Persisted project plan changed; reconciliation required");
        return row;
    }
    private static boolean approved(Map<String, String> row) { return row.get("architect") != null && row.get("manager") != null; }
    private void requireBaseline() {
        if (!definition.pinnedTarget().equals(GitAnalysisSnapshot.headCommit(repository))
                || !definition.pinnedTarget().equals(GitAnalysisSnapshot.localRefCommit(repository, definition.targetRef())))
            throw new IllegalStateException("Repository baseline changed; revalidate the plan");
    }
    @Override public boolean approved() { return approved(checkedRow()); }
    @Override public void requireExecutable() {
        if (!approved()) throw new IllegalStateException("Project plan requires architecture and execution approval");
        requireBaseline();
    }
    @Override public boolean configured() { return true; }
    @Override public String planScope() { return fingerprint; }
    public ProjectDefinition definition() { return definition; }
    @Override public Path repository() { return repository; }
    @Override public DemoProjectOverview overview() { return overview; }
    @Override public Set<String> forbiddenPaths() { return definition.forbiddenPaths(); }
    @Override public ProjectScheduling scheduling() { return definition.scheduling(); }
    @Override public List<IntegrationProfiles.Check> executionChecks(String taskId) {
        return definition.validationProfiles().stream().filter(profile -> profile.id().equals(definition.taskProfiles().get(taskId)))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown task validation profile")).checks();
    }
    @Override public Set<String> taskEnvironments(String taskId) {
        return definition.taskResources().get(taskId).stream()
                .filter(id -> definition.resources().get(id).kind().equals("ENVIRONMENT"))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    @Override public Set<String> semanticResources(String taskId, String kind) {
        return definition.taskResources().get(taskId).stream().filter(id -> definition.resources().get(id).kind().equals(kind))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    @Override public Set<String> semanticConsumers(Set<String> resourceIds) {
        return definition.taskResources().entrySet().stream().filter(entry -> !java.util.Collections.disjoint(entry.getValue(), resourceIds))
                .map(Map.Entry::getKey).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    @Override public List<IntegrationProfiles.Profile> validationProfiles() { return definition.validationProfiles(); }
    @Override public void requireValidationProfile(List<String> taskIds, IntegrationProfiles.Profile selected) {
        for (String taskId : taskIds) {
            String profileId = definition.taskProfiles().get(taskId);
            var required = definition.validationProfiles().stream().filter(profile -> profile.id().equals(profileId))
                    .findFirst().orElseThrow(() -> new IllegalStateException("Task has no approved validation profile"));
            if (!selected.checks().containsAll(required.checks()))
                throw new IllegalStateException("Integration profile does not cover the approved checks for " + taskId);
        }
    }
    @Override public String targetRef(String fallback) { return definition.targetRef(); }

    static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    static JsonNode canonical(JsonNode value, ObjectMapper mapper) {
        if (value.isObject()) {
            var sorted = mapper.createObjectNode();
            value.properties().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> sorted.set(entry.getKey(), canonical(entry.getValue(), mapper)));
            return sorted;
        }
        if (value.isArray()) { var array = mapper.createArrayNode(); value.forEach(item -> array.add(canonical(item, mapper))); return array; }
        return value;
    }
}
