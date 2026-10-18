package io.refactorcontrolplane.integration;

import io.refactorcontrolplane.project.ProjectSource;
import io.refactorcontrolplane.governance.LocalIdentityRegistry.Principal;
import io.refactorcontrolplane.governance.ProjectRole;
import io.refactorcontrolplane.mcp.McpProjectRuntime;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class IntegrationService {
    private final McpProjectRuntime runtime;
    private final ProjectSource source;
    private final IntegrationStore store;
    private final IntegrationProfiles profiles;
    private final IntegrationGit git;
    private final String projectId;
    private final String repository;
    private final String targetRef;

    public IntegrationService(ProjectSource project, McpProjectRuntime runtime, IntegrationStore store,
            IntegrationProfiles profiles, @Value("${control-plane.integration-target:refs/heads/main}") String targetRef) {
        this.source = project;
        store.bindScope(project.planScope());
        this.runtime = runtime; this.store = store; this.profiles = profiles; this.targetRef = project.targetRef(targetRef);
        this.projectId = project.overview().projectId(); this.repository = project.repository().toAbsolutePath().normalize().toString();
        this.git = new IntegrationGit(project.repository());
        runtime.integrationMergeGuard(task -> {
            if (store.enrolled(task)) throw new IllegalStateException("Task belongs to an integration; request an integration proposal with current combined evidence");
        });
    }

    public record Create(String idempotencyKey, long expectedVersion, List<String> taskIds, String profileId) {}
    public record Validate(String idempotencyKey, String expectedFingerprint) {}
    public record Reconcile(String idempotencyKey,String expectedFingerprint,String validationId,String reason) {}
    public record Propose(String idempotencyKey, String expectedFingerprint, String validationId) {}
    public record Plan(String id, String createdAt, String fingerprint, String repository, String targetRef,
            String targetCommit, List<McpProjectRuntime.IntegrationInput> inputs, IntegrationProfiles.Profile profile,
            IntegrationReport staticReport) {}
    public record Candidate(Plan plan, String state, IntegrationGit.Assembly assembly, String latestValidation) {}
    public record CheckResult(String checkId, List<String> argv, String workingDirectory, int exitCode,
            boolean timedOut, boolean truncated, String stdout, String stderr) {}
    public record Validation(String id, String candidateId, String fingerprint, String commitSha, String actor,
            String state, String startedAt, String completedAt, List<CheckResult> checks, String detail) {}
    public record Proposal(String id, String candidateId, String fingerprint, String validationId, String branch,
            String commitSha, String targetRef, String targetCommit, String actor, String createdAt,
            boolean automaticallyMergeMain, String mergePolicy) {}
    public record View(Candidate candidate, String effectiveState, boolean currentlyValid, String invalidReason, Validation validation) {}

    public List<String> list(Principal principal) { authorize(principal, null); return store.candidates(); }
    /** All lifecycle changes use the same lock order as create/propose: integration then runtime. */
    public synchronized <T> T withPlanLock(java.util.function.Supplier<T> action) {
        synchronized (runtime) { return action.get(); }
    }
    public void requireNoUnknownLatestValidation() {
        for (String id:store.currentCandidates()) {
            Candidate candidate=candidate(id);
            if (candidate.latestValidation()!=null && store.read(store.get(candidate.latestValidation()),Validation.class).state().equals("RUNNING"))
                throw new IllegalStateException("Latest validation is unknown; inspect its worktree and run a fresh attempt before switching plans");
        }
    }
    public synchronized void requireBaselineCandidate(String id, String commit, String target) {
        Candidate candidate = candidate(id); requireCurrent(candidate);
        if (!candidate.state().equals("READY") || candidate.latestValidation() == null
                || !candidate.assembly().commit().equals(commit) || !candidate.plan().targetCommit().equals(target))
            throw new IllegalStateException("Baseline must be the current validated candidate commit");
        Validation validation = store.read(store.get(candidate.latestValidation()), Validation.class);
        if (!validation.state().equals("PASSED") || !validation.fingerprint().equals(candidate.plan().fingerprint())
                || !validation.commitSha().equals(commit)) throw new IllegalStateException("Latest combined validation must pass");
        runtime.requireIntegrationAccepted(taskIds(candidate));
    }
    public View get(Principal principal, String id) {
        authorize(principal, null); Candidate candidate = candidate(id);
        String invalid = invalidReason(candidate);
        Validation validation = candidate.latestValidation() == null ? null
                : store.read(store.get(candidate.latestValidation()), Validation.class);
        return new View(candidate, invalid.isEmpty() ? candidate.state() : "STALE", invalid.isEmpty(), invalid, validation);
    }

    public synchronized View create(Principal actor, Create request) {
        authorize(actor, ProjectRole.ENGINEERING_LEAD); key(request.idempotencyKey());
        if (request.profileId() == null || request.profileId().isBlank() || request.taskIds() == null)
            throw new IllegalArgumentException("profileId and taskIds are required");
        // Normalize sets so equivalent task order cannot create a different operation.
        Create normalized = new Create(request.idempotencyKey(), request.expectedVersion(), request.taskIds().stream().sorted().toList(), request.profileId());
        var existing = store.byKey(request.idempotencyKey());
        IntegrationStore.Entry entry;
        if (existing.isPresent()) { entry = existing.get(); replay(entry, actor, normalized, "candidate"); }
        else {
            synchronized (runtime) {
                if (request.expectedVersion() != runtime.contextVersion()) throw new IllegalStateException("Context version changed");
                var inputs = runtime.integrationInputs(request.taskIds()); independent(actor, inputs);
                var profile = profiles.load(request.profileId());
                source.requireValidationProfile(request.taskIds(), profile);
                String target = git.target(targetRef);
                String fingerprint = fingerprint(repository, targetRef, target, inputs, profile);
                var report = new IntegrationConflictAnalyzer(io.refactorcontrolplane.ownership.RepositoryPathPolicy.caseInsensitive())
                        .analyze(inputs.stream().map(input -> new ChangesetDescriptor(input.taskId(), git.changedPaths(input),
                                input.contractVersion(), input.schemaVersion())).toList());
                Plan plan = new Plan(UUID.randomUUID().toString(), Instant.now().toString(), fingerprint,
                        repository, targetRef, target, inputs, profile, report);
                boolean incompatible = report.conflicts().stream().anyMatch(conflict -> !conflict.kind().equals("PATH"));
                entry = store.insert(plan.id(), "candidate", request.idempotencyKey(), actor.subjectId(), normalized,
                        new Candidate(plan, incompatible ? "CONFLICTED" : "PREPARING", incompatible
                                ? new IntegrationGit.Assembly(null, null, List.of(), "Incompatible API or schema versions; revalidate the affected tasks") : null, null),
                        inputs.stream().map(McpProjectRuntime.IntegrationInput::taskId).toList());
            }
        }
        Candidate candidate = store.read(entry, Candidate.class);
        if (candidate.state().equals("PREPARING")) {
            requireCurrent(candidate);
            var assembly = git.assemble(candidate.plan());
            candidate = new Candidate(candidate.plan(), assembly.commit() == null ? "CONFLICTED" : "ASSEMBLED", assembly, null);
            entry = store.save(entry, candidate);
        }
        if (candidate.state().equals("ASSEMBLED")) {
            requireCurrent(candidate);
            git.publish(candidate.plan(), candidate.assembly().commit());
            store.save(entry, new Candidate(candidate.plan(), "READY", candidate.assembly(), candidate.latestValidation()));
        }
        return get(actor, entry.id());
    }

    public synchronized Validation validate(Principal actor, String id, Validate request) {
        authorize(actor, ProjectRole.QA_LEAD); key(request.idempotencyKey());
        Candidate candidate = candidate(id); independent(actor, candidate.plan().inputs());
        expected(candidate, request.expectedFingerprint());
        var existing = store.byKey(request.idempotencyKey());
        if (existing.isPresent()) {
            replay(existing.get(), actor, List.of(id, request), "validation");
            Validation prior = store.read(existing.get(), Validation.class);
            if (prior.state().equals("RUNNING")) throw new IllegalStateException("Validation completion is unknown; inspect its directory and start a new attempt with a new key");
            return prior;
        }
        if (!candidate.state().equals("READY")) throw new IllegalStateException("Candidate is not ready for validation");
        String validationId = UUID.randomUUID().toString(), start = Instant.now().toString();
        Validation running = new Validation(validationId, id, candidate.plan().fingerprint(), candidate.assembly().commit(),
                actor.subjectId(), "RUNNING", start, null, List.of(), "");
        IntegrationStore.Entry attempt;
        synchronized (runtime) {
            expected(candidate,request.expectedFingerprint());
            attempt = store.beginValidation(store.get(id), candidate, running, request.idempotencyKey(), actor.subjectId(), List.of(id, request));
        }
        var results = new ArrayList<CheckResult>();
        String detail = ""; boolean passed = true;
        try {
            Path tree = git.validationTree(candidate.plan(), candidate.assembly().commit(), validationId).toRealPath();
            if (!git.clean(tree, candidate.assembly().commit())) throw new IllegalStateException("Validation tree does not match candidate");
            for (var check : candidate.plan().profile().checks()) {
                Path cwd = tree.resolve(check.workingDirectory()).normalize().toRealPath();
                if (!cwd.startsWith(tree)) throw new IllegalStateException("Configured working directory escapes validation tree");
                var result = ControlledProcess.run(cwd, check.argv(), Map.of("GIT_CONFIG_NOSYSTEM", "1",
                        "GIT_CONFIG_GLOBAL", System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null"),
                        new byte[0], check.timeoutSeconds(), 65536);
                results.add(new CheckResult(check.id(), check.argv(), check.workingDirectory(), result.exitCode(),
                        result.timedOut(), result.truncated(), result.out(), result.err()));
                if (!result.passed()) { passed = false; break; }
            }
            if (!git.clean(tree, candidate.assembly().commit())) throw new IllegalStateException("Validation modified tracked source or HEAD");
            requireCurrent(candidate);
        } catch (RuntimeException | java.io.IOException ex) { passed = false; detail = ex.getMessage(); }
        Validation completed = new Validation(validationId, id, running.fingerprint(), running.commitSha(), actor.subjectId(),
                passed ? "PASSED" : "FAILED", start, Instant.now().toString(), List.copyOf(results), detail);
        synchronized (runtime) { store.save(attempt, completed); }
        return completed;
    }

    public synchronized Proposal propose(Principal actor, String id, Propose request) {
        authorize(actor, ProjectRole.ENGINEERING_LEAD); key(request.idempotencyKey());
        synchronized (runtime) {
            Candidate candidate = candidate(id); independent(actor, candidate.plan().inputs());
            expected(candidate, request.expectedFingerprint());
            if (!candidate.state().equals("READY") || candidate.latestValidation() == null
                    || !candidate.latestValidation().equals(request.validationId())) throw new IllegalStateException("Latest validation must be specified");
            Validation validation = store.read(store.get(request.validationId()), Validation.class);
            if (!validation.state().equals("PASSED") || !validation.candidateId().equals(id)
                    || !validation.fingerprint().equals(candidate.plan().fingerprint())
                    || !validation.commitSha().equals(candidate.assembly().commit())) throw new IllegalStateException("No current passing combined validation");
            runtime.requireIntegrationAccepted(taskIds(candidate));
            var existing = store.byKey(request.idempotencyKey());
            if (existing.isPresent()) {
                replay(existing.get(), actor, List.of(id, request), "proposal");
                return store.read(existing.get(), Proposal.class);
            }
            Proposal proposal = new Proposal(UUID.randomUUID().toString(), id, candidate.plan().fingerprint(), validation.id(),
                    IntegrationGit.branch(id), candidate.assembly().commit(), candidate.plan().targetRef(),
                    candidate.plan().targetCommit(), actor.subjectId(), Instant.now().toString(), false, "HUMAN_ONLY");
            store.insert(proposal.id(), "proposal", request.idempotencyKey(), actor.subjectId(), List.of(id, request), proposal, List.of());
            return proposal;
        }
    }

    /** Records a human disposition; never reruns an unknown attempt or turns it into passing evidence. */
    public synchronized Validation reconcileValidation(Principal actor,String id,Reconcile request) {
        authorize(actor,ProjectRole.QA_LEAD); key(request.idempotencyKey());
        synchronized(runtime) {
            source.requireExecutable();
            Candidate candidate=candidate(id); independent(actor,candidate.plan().inputs());
            if (!store.currentCandidates().contains(id) || !candidate.plan().fingerprint().equals(request.expectedFingerprint()))
                throw new IllegalStateException("Candidate scope or fingerprint changed");
            if (request.reason()==null || request.reason().isBlank() || request.reason().length()>2000)
                throw new IllegalArgumentException("Supply a reconciliation reason of 1 to 2000 characters");
            var previous=store.byKey(request.idempotencyKey());
            if(previous.isPresent()) { replay(previous.get(),actor,List.of(id,request),"validation"); return store.read(previous.get(),Validation.class); }
            if(!Objects.equals(candidate.latestValidation(),request.validationId())) throw new IllegalStateException("Latest validation changed");
            Validation unknown=store.read(store.get(request.validationId()),Validation.class);
            if(!unknown.state().equals("RUNNING")) throw new IllegalStateException("Only an unknown validation can be reconciled");
            Validation disposition=new Validation(UUID.randomUUID().toString(),id,unknown.fingerprint(),unknown.commitSha(),actor.subjectId(),
                    "ABANDONED",unknown.startedAt(),Instant.now().toString(),List.of(),"Reconciles unknown attempt "+unknown.id()+": "+request.reason());
            // Retain the original RUNNING row and atomically link the new disposition as latest.
            store.beginValidation(store.get(id),candidate,disposition,request.idempotencyKey(),actor.subjectId(),List.of(id,request));
            return disposition;
        }
    }

    private Candidate candidate(String id) {
        var entry = store.get(id);
        if (!entry.kind().equals("candidate")) throw new IllegalArgumentException("Not a candidate ID");
        return store.read(entry, Candidate.class);
    }
    private List<String> taskIds(Candidate candidate) { return candidate.plan().inputs().stream().map(McpProjectRuntime.IntegrationInput::taskId).toList(); }
    private String invalidReason(Candidate candidate) {
        try {
            if (!candidate.plan().repository().equals(repository) || !candidate.plan().targetRef().equals(targetRef)) return "Project repository or target changed";
            var inputs = runtime.integrationInputs(taskIds(candidate));
            source.requireValidationProfile(taskIds(candidate), profiles.load(candidate.plan().profile().id()));
            String current = fingerprint(repository, targetRef, git.target(targetRef), inputs, profiles.load(candidate.plan().profile().id()));
            if (!current.equals(candidate.plan().fingerprint())) return "Submitted commits, semantic versions, target or validation configuration changed";
            if (candidate.state().equals("READY") && !git.target(IntegrationGit.branch(candidate.plan().id())).equals(candidate.assembly().commit()))
                return "Published candidate branch changed";
            return "";
        } catch (RuntimeException ex) { return "Inputs cannot be verified: " + ex.getMessage(); }
    }
    private void requireCurrent(Candidate candidate) {
        String reason = invalidReason(candidate);
        if (!reason.isEmpty()) throw new IllegalStateException("Stale integration: " + reason);
    }
    private void expected(Candidate candidate, String expected) {
        if (!candidate.plan().fingerprint().equals(expected)) throw new IllegalStateException("Candidate fingerprint mismatch");
        requireCurrent(candidate);
    }
    private String fingerprint(String repo, String ref, String target, Object inputs, Object profile) {
        return fingerprint(source,store,repo,ref,target,inputs,profile);
    }
    static String fingerprint(ProjectSource source,IntegrationStore store,String repo,String ref,String target,Object inputs,Object profile) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(store.json(source.configured() ? List.of(repo, ref, target, inputs, profile, source.planScope())
                        : List.of(repo, ref, target, inputs, profile)).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private void authorize(Principal actor, ProjectRole role) {
        if (!actor.projectId().equals(projectId) || (role != null && !actor.roles().contains(role))) throw new SecurityException("Project role required: " + role);
    }
    private void independent(Principal actor, List<McpProjectRuntime.IntegrationInput> inputs) {
        if (inputs.stream().anyMatch(input -> input.owner().equals(actor.subjectId()))) throw new SecurityException("Executors cannot approve their own integration");
    }
    private static void key(String key) {
        if (key == null || key.isBlank() || key.length() > 160) throw new IllegalArgumentException("idempotencyKey must contain 1 to 160 characters");
    }
    private void replay(IntegrationStore.Entry entry, Principal actor, Object request, String kind) {
        if (!entry.kind().equals(kind) || !entry.actor().equals(actor.subjectId()) || !entry.request().equals(store.json(request)))
            throw new IllegalStateException("Idempotency key belongs to another operation or actor");
    }
}
