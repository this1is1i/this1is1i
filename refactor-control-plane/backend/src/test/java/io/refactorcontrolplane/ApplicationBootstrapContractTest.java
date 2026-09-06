package io.refactorcontrolplane;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

class ApplicationBootstrapContractTest {

    @Test
    void exposesASpringBootApplicationEntryPoint() {
        Class<?> applicationType = assertDoesNotThrow(
                () -> Class.forName("io.refactorcontrolplane.RefactorControlPlaneApplication"));

        assertNotNull(applicationType.getAnnotation(SpringBootApplication.class));
        assertDoesNotThrow(() -> applicationType.getMethod("main", String[].class));
    }
}
