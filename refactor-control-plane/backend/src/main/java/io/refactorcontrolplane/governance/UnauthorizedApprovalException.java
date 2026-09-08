package io.refactorcontrolplane.governance;

public class UnauthorizedApprovalException extends RuntimeException {
    public UnauthorizedApprovalException(QualityGate gate, ProjectRole role) {
        super(role + " cannot approve " + gate + "; required role is " + gate.approvingRole());
    }
}
