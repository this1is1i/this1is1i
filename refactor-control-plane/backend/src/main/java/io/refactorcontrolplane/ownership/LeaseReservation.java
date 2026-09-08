package io.refactorcontrolplane.ownership;

import java.util.List;
import java.util.Optional;

public record LeaseReservation(boolean accepted, Optional<PathLease> lease, List<LeaseConflict> conflicts) {

    public LeaseReservation {
        lease = lease == null ? Optional.empty() : lease;
        conflicts = List.copyOf(conflicts);
        if (accepted == lease.isEmpty()) {
            throw new IllegalArgumentException("accepted reservation must contain exactly one lease");
        }
        if (accepted && !conflicts.isEmpty()) {
            throw new IllegalArgumentException("accepted reservation must not contain conflicts");
        }
        if (!accepted && conflicts.isEmpty()) {
            throw new IllegalArgumentException("rejected reservation must contain conflicts");
        }
    }

    static LeaseReservation accepted(PathLease lease) {
        return new LeaseReservation(true, Optional.of(lease), List.of());
    }

    static LeaseReservation rejected(List<LeaseConflict> conflicts) {
        return new LeaseReservation(false, Optional.empty(), conflicts);
    }
}
