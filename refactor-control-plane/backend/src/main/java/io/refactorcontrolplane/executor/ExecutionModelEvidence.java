package io.refactorcontrolplane.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.refactorcontrolplane.executor.ExecutionProtocol.*;
import io.refactorcontrolplane.governance.EvidenceStore;

public class ExecutionModelEvidence {
    private record Receipt(String provenance, String requestHash, String executorFingerprint, String owner,
            long assignmentEpoch, java.util.List<ExecutorModelRun> modelRuns) {}
    private final EvidenceStore store;
    private final ObjectMapper mapper;
    public ExecutionModelEvidence(EvidenceStore store, ObjectMapper mapper) { this.store = store; this.mapper = mapper; }

    public void validate(Request request, Result result) {
        store.validateContent(content(request, result));
    }
    public String record(Request request, Result result, EvidenceStore.Binding binding) {
        validate(request, result);
        return store.append("executor-models:" + request.execution().executionId(), binding, "EXECUTOR_MODEL_RUNS",
                request.executorId(), content(request, result)).id();
    }
    public void verify(Run run) {
        if (run.result() == null || run.result().modelRuns() == null || run.result().modelRuns().isEmpty()) {
            if (run.modelEvidenceRef() != null) throw new IllegalStateException("Unexpected model evidence binding");
            return;
        }
        if (run.modelEvidenceRef() == null) throw new IllegalStateException("Missing model receipt evidence");
        var evidence = store.read(run.request().planScope(), run.modelEvidenceRef());
        var binding = evidence.binding(); var request = run.request();
        if (!"SUCCEEDED".equals(run.state()) || !"EXECUTOR_MODEL_RUNS".equals(evidence.kind())
                || !request.executorId().equals(evidence.author()) || !request.execution().taskId().equals(binding.taskId())
                || !request.execution().executionId().equals(binding.executionId()) || request.contextVersion() != binding.contextVersion()
                || !run.result().commitSha().equals(binding.commitSha())
                || !receipt(request, run.result()).equals(mapper.convertValue(evidence.content(), Receipt.class)))
            throw new IllegalStateException("Model receipt evidence binding changed");
    }
    private com.fasterxml.jackson.databind.JsonNode content(Request request, Result result) {
        return mapper.valueToTree(receipt(request, result));
    }
    private Receipt receipt(Request request, Result result) {
        return new Receipt("EXECUTOR_REPORTED", request.requestHash(), request.executorFingerprint(), request.owner(), request.assignmentEpoch(), result.modelRuns());
    }
}
