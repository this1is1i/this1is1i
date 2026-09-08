package io.refactorcontrolplane.model;

public final class MockModelGateway implements ModelGateway {
    @Override
    public ModelExecution execute(ModelRequest request) {
        String output = "--- mock patch ---\n# instruction: " + request.instruction();
        return new ModelExecution(output, new ModelAudit(
                "mock-enterprise-model",
                request.promptVersion(),
                ModelHashes.sha256(request.codeContext()),
                ModelHashes.sha256(output),
                request.tools(),
                0));
    }
}
