package io.refactorcontrolplane.ownership;

import java.util.UUID;

public class LeaseNotFoundException extends RuntimeException {

    public LeaseNotFoundException(UUID leaseId) {
        super("Lease not found: " + leaseId);
    }
}
