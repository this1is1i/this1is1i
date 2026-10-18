package io.refactorcontrolplane.integration;

import com.fasterxml.jackson.databind.*;
import io.refactorcontrolplane.governance.*;
import io.refactorcontrolplane.mcp.McpProjectRuntime;
import io.refactorcontrolplane.project.ProjectSource;
import java.util.*;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Called under the runtime lock. Never acquires the IntegrationService lock. */
@Service
public class GateEvidencePolicy {
    private final EvidenceStore evidence;
    private final IntegrationStore integrations;
    private final IntegrationProfiles profiles;
    private final ProjectSource source;
    private final ObjectMapper mapper;
    private final IntegrationGit git;
    private final String repository, targetRef;
    public GateEvidencePolicy(EvidenceStore evidence, IntegrationStore integrations, IntegrationProfiles profiles,
            ProjectSource source, ObjectMapper mapper, @Value("${control-plane.integration-target:refs/heads/main}") String targetRef) {
        this.evidence=evidence; this.integrations=integrations; this.profiles=profiles; this.source=source; this.mapper=mapper;
        this.git=new IntegrationGit(source.repository()); this.repository=source.repository().toAbsolutePath().normalize().toString();
        this.targetRef=source.targetRef(targetRef);
    }
    public void requireSingleTaskProposal(String taskId) {
        // Runtime recovery precedes IntegrationService construction and its mutable store scope binding.
        if (integrations.enrolled(taskId,source.planScope()))
            throw new IllegalStateException("Task belongs to an integration; request an integration proposal with current combined evidence");
    }
    public String record(String key, EvidenceStore.Binding binding, QualityGate gate, boolean passed, String author, String owner,
            JsonNode args, Function<List<String>,List<McpProjectRuntime.IntegrationInput>> currentInputs) {
        String ref;
        if (args.has("evidenceRef")) {
            if (args.has("statement") || args.has("candidateId") || args.has("validationId")) throw new IllegalArgumentException("Choose an existing evidence ID or supply new evidence");
            ref=text(args,"evidenceRef");
        } else {
            var content=mapper.createObjectNode().put("passed",passed);
            if (manual(gate)) {
                if (args.has("candidateId") || args.has("validationId")) throw new IllegalArgumentException("Manual gate requires a statement");
                String statement=text(args,"statement");
                if (statement.length()>16000) throw new IllegalArgumentException("Statement exceeds limit");
                content.put("statement",statement);
            } else {
                if (args.has("statement")) throw new IllegalArgumentException("Test gates require platform validation, not a statement");
                String candidate=text(args,"candidateId"), validation=text(args,"validationId");
                var result=validation(candidate,validation,binding,passed,currentInputs);
                content.put("candidateId",candidate).put("validationId",validation).put("validationHash",digest(integrations.json(result)));
            }
            ref=evidence.append(key,binding,gate.name(),author,content).id();
        }
        verify(ref,binding,gate,passed,author,owner,currentInputs); return ref;
    }
    public void verify(String ref,EvidenceStore.Binding binding,QualityGate gate,boolean passed,String author,String owner,
            Function<List<String>,List<McpProjectRuntime.IntegrationInput>> currentInputs) {
        if (ref==null || !ref.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("A stored evidence ID is required");
        var item=evidence.read(binding.planScope(),ref);
        if (!item.binding().equals(binding) || !item.kind().equals(gate.name()) || item.author().equals(owner)
                || (author!=null && !item.author().equals(author))) throw new IllegalStateException("Evidence binding or author does not match this approval");
        var content=item.content();
        if (!content.path("passed").isBoolean() || content.path("passed").asBoolean()!=passed) throw new IllegalStateException("Evidence verdict does not match approval");
        if (manual(gate)) text(content,"statement");
        else {
            var result=validation(text(content,"candidateId"),text(content,"validationId"),binding,passed,currentInputs);
            if (!digest(integrations.json(result)).equals(text(content,"validationHash"))) throw new IllegalStateException("Platform validation content changed");
        }
    }
    private IntegrationService.Validation validation(String candidateId,String validationId,EvidenceStore.Binding binding,boolean passed,
            Function<List<String>,List<McpProjectRuntime.IntegrationInput>> currentInputs) {
        var entry=integrations.getScoped(candidateId,"candidate",binding.planScope());
        if (!entry.kind().equals("candidate")) throw new IllegalArgumentException("Expected a candidate ID");
        var candidate=integrations.read(entry,IntegrationService.Candidate.class); var plan=candidate.plan();
        var ids=plan.inputs().stream().map(McpProjectRuntime.IntegrationInput::taskId).toList();
        if (!ids.contains(binding.taskId()) || !candidate.state().equals("READY") || !Objects.equals(candidate.latestValidation(),validationId)
                || !plan.repository().equals(repository) || !plan.targetRef().equals(targetRef)) throw new IllegalStateException("Evidence must use the latest validation of a current candidate containing this task");
        var inputs=currentInputs.apply(ids); var profile=profiles.load(plan.profile().id()); source.requireValidationProfile(ids,profile);
        String fingerprint=IntegrationService.fingerprint(source,integrations,repository,targetRef,git.target(targetRef),inputs,profile);
        if (!fingerprint.equals(plan.fingerprint()) || !git.target(IntegrationGit.branch(candidateId)).equals(candidate.assembly().commit()))
            throw new IllegalStateException("Candidate inputs, profile, target or branch changed");
        var validationEntry=integrations.getScoped(validationId,"validation",binding.planScope());
        if (!validationEntry.kind().equals("validation")) throw new IllegalArgumentException("Expected a validation ID");
        var result=integrations.read(validationEntry,IntegrationService.Validation.class);
        if (!result.candidateId().equals(candidateId) || !result.fingerprint().equals(fingerprint)
                || !result.commitSha().equals(candidate.assembly().commit()) || result.completedAt()==null
                || !result.state().equals(passed?"PASSED":"FAILED") || inputs.stream().anyMatch(input->input.owner().equals(result.actor())))
            throw new IllegalStateException("Validation is unknown, failed, obsolete or not independently produced");
        if (passed) {
            if (result.checks().size()!=profile.checks().size()) throw new IllegalStateException("Validation checks are incomplete");
            for (int i=0;i<profile.checks().size();i++) {
                var approved=profile.checks().get(i); var actual=result.checks().get(i);
                if (!actual.checkId().equals(approved.id()) || !actual.argv().equals(approved.argv()) || !actual.workingDirectory().equals(approved.workingDirectory())
                        || actual.exitCode()!=0 || actual.timedOut() || actual.truncated()) throw new IllegalStateException("Validation does not match the approved checks");
            }
        }
        return result;
    }
    private static boolean manual(QualityGate gate) { return gate==QualityGate.CODE_REVIEW || gate==QualityGate.BUSINESS_ACCEPTANCE; }
    private static String text(JsonNode node,String key) {
        if (!node.path(key).isTextual() || node.path(key).asText().isBlank()) throw new IllegalArgumentException("Missing evidence field: "+key);
        return node.path(key).asText();
    }
    private static String digest(String value) { return io.refactorcontrolplane.executor.ExecutorSettings.hash(value); }
}
