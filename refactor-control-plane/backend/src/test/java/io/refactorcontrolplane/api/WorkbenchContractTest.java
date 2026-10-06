package io.refactorcontrolplane.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties={"control-plane.identity-file=classpath:review-identities.json", "spring.datasource.url=jdbc:h2:mem:workbench"})
@AutoConfigureMockMvc
class WorkbenchContractTest {
    @Autowired MockMvc mvc;
    @Test void snapshotRequiresIdentityAndReturnsAuthoritativeTasksWithoutAssignmentCredentials() throws Exception {
        mvc.perform(get("/api/v1/workbench")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/workbench").header("Authorization", "Bearer lead-test-token"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.subjectId").value("lead"))
                .andExpect(jsonPath("$.roles[0]").value("ENGINEERING_LEAD"))
                .andExpect(jsonPath("$.tasks", hasSize(7)))
                .andExpect(jsonPath("$.tasks[0].status").value("READY"))
                .andExpect(jsonPath("$.tasks[0].owner").value("task:WP-100"))
                .andExpect(jsonPath("$.tasks[0].dependencies").isArray())
                .andExpect(jsonPath("$.tasks[0].gates.CODE_REVIEW").value(false))
                .andExpect(jsonPath("$.profiles", hasSize(0)))
                .andExpect(content().string(not(containsString("taskToken"))));
    }
}
