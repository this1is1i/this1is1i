package io.refactorcontrolplane.ownership;

public class LeaseOwnerMismatchException extends RuntimeException {

    public LeaseOwnerMismatchException(String expectedExecutionId, String actualExecutionId) {
        super("Lease belongs to execution " + actualExecutionId + ", not " + expectedExecutionId);
    }
}
