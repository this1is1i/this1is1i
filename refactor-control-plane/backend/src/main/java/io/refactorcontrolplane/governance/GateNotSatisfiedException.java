package io.refactorcontrolplane.governance;

public class GateNotSatisfiedException extends RuntimeException {
    public GateNotSatisfiedException(String taskId) {
        super("Required quality gates have not passed for task " + taskId);
    }
}
