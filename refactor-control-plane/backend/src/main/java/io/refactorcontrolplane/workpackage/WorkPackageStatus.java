package io.refactorcontrolplane.workpackage;

public enum WorkPackageStatus {
    DRAFT,
    READY,
    CLAIMED,
    RUNNING,
    SUBMITTED,
    CODE_REVIEW,
    TESTING,
    ACCEPTED,
    BLOCKED,
    STALE,
    REJECTED,
    CANCELLED;

    public boolean canTransitionTo(WorkPackageStatus target) {
        return switch (this) {
            case DRAFT -> target == READY || target == CANCELLED;
            case READY -> target == CLAIMED || target == BLOCKED
                    || target == STALE || target == CANCELLED;
            case CLAIMED -> target == RUNNING || target == BLOCKED
                    || target == STALE || target == CANCELLED;
            case RUNNING -> target == SUBMITTED || target == BLOCKED
                    || target == STALE || target == CANCELLED;
            case SUBMITTED -> target == CODE_REVIEW || target == REJECTED || target == STALE;
            case CODE_REVIEW -> target == TESTING || target == REJECTED || target == STALE;
            case TESTING -> target == ACCEPTED || target == REJECTED || target == STALE;
            case BLOCKED, STALE, REJECTED -> target == READY || target == CANCELLED;
            case ACCEPTED, CANCELLED -> false;
        };
    }
}
