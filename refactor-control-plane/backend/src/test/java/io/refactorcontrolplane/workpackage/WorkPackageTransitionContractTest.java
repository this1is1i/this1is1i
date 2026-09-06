package io.refactorcontrolplane.workpackage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkPackageTransitionContractTest {

    @Test
    void enforcesTheApprovedTransitionMatrix() {
        Method transitionMethod = assertDoesNotThrow(
                () -> WorkPackageStatus.class.getMethod("canTransitionTo", WorkPackageStatus.class));

        Map<WorkPackageStatus, EnumSet<WorkPackageStatus>> expected = approvedTransitions();

        for (WorkPackageStatus current : WorkPackageStatus.values()) {
            for (WorkPackageStatus target : WorkPackageStatus.values()) {
                boolean actual = assertDoesNotThrow(
                        () -> (boolean) transitionMethod.invoke(current, target));
                assertEquals(
                        expected.get(current).contains(target),
                        actual,
                        () -> "Unexpected transition result for " + current + " -> " + target);
            }
        }
    }

    private static Map<WorkPackageStatus, EnumSet<WorkPackageStatus>> approvedTransitions() {
        Map<WorkPackageStatus, EnumSet<WorkPackageStatus>> transitions =
                new EnumMap<>(WorkPackageStatus.class);
        transitions.put(WorkPackageStatus.DRAFT,
                EnumSet.of(WorkPackageStatus.READY, WorkPackageStatus.CANCELLED));
        transitions.put(WorkPackageStatus.READY,
                EnumSet.of(WorkPackageStatus.CLAIMED, WorkPackageStatus.BLOCKED,
                        WorkPackageStatus.STALE, WorkPackageStatus.CANCELLED));
        transitions.put(WorkPackageStatus.CLAIMED,
                EnumSet.of(WorkPackageStatus.RUNNING, WorkPackageStatus.BLOCKED,
                        WorkPackageStatus.STALE, WorkPackageStatus.CANCELLED));
        transitions.put(WorkPackageStatus.RUNNING,
                EnumSet.of(WorkPackageStatus.SUBMITTED, WorkPackageStatus.BLOCKED,
                        WorkPackageStatus.STALE, WorkPackageStatus.CANCELLED));
        transitions.put(WorkPackageStatus.SUBMITTED,
                EnumSet.of(WorkPackageStatus.CODE_REVIEW, WorkPackageStatus.REJECTED,
                        WorkPackageStatus.STALE));
        transitions.put(WorkPackageStatus.CODE_REVIEW,
                EnumSet.of(WorkPackageStatus.TESTING, WorkPackageStatus.REJECTED,
                        WorkPackageStatus.STALE));
        transitions.put(WorkPackageStatus.TESTING,
                EnumSet.of(WorkPackageStatus.ACCEPTED, WorkPackageStatus.REJECTED,
                        WorkPackageStatus.STALE));
        transitions.put(WorkPackageStatus.BLOCKED,
                EnumSet.of(WorkPackageStatus.READY, WorkPackageStatus.CANCELLED));
        transitions.put(WorkPackageStatus.STALE,
                EnumSet.of(WorkPackageStatus.READY, WorkPackageStatus.CANCELLED));
        transitions.put(WorkPackageStatus.REJECTED,
                EnumSet.of(WorkPackageStatus.READY, WorkPackageStatus.CANCELLED));
        transitions.put(WorkPackageStatus.ACCEPTED, EnumSet.noneOf(WorkPackageStatus.class));
        transitions.put(WorkPackageStatus.CANCELLED, EnumSet.noneOf(WorkPackageStatus.class));
        return transitions;
    }
}
