package io.refactorcontrolplane.workpackage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class WorkPackageStatusContractTest {

    @Test
    void exposesTheApprovedLifecycleStates() {
        Class<?> statusType = assertDoesNotThrow(
                () -> Class.forName("io.refactorcontrolplane.workpackage.WorkPackageStatus"));

        Set<String> actualStates = Arrays.stream(statusType.getEnumConstants())
                .map(Object::toString)
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                "DRAFT",
                "READY",
                "CLAIMED",
                "RUNNING",
                "SUBMITTED",
                "CODE_REVIEW",
                "TESTING",
                "ACCEPTED",
                "BLOCKED",
                "STALE",
                "REJECTED",
                "CANCELLED"), actualStates);
    }
}
