package io.refactorcontrolplane.ownership;

public class LeaseVersionConflictException extends RuntimeException {

    public LeaseVersionConflictException(long expected, long actual) {
        super("Lease version mismatch: expected " + expected + " but was " + actual);
    }
}
