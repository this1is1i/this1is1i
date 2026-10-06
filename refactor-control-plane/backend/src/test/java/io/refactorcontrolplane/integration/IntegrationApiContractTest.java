package io.refactorcontrolplane.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"control-plane.identity-file=classpath:review-identities.json",
        "spring.datasource.url=jdbc:h2:mem:integration-api"})
@AutoConfigureMockMvc
class IntegrationApiContractTest {
    @Autowired MockMvc mvc;

    @Test void candidatesRequireProjectAuthentication() throws Exception {
        mvc.perform(get("/api/v1/integrations/candidates")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/integrations/candidates").header("Authorization", "Bearer lead-test-token"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @Test void commandsCannotBeSuppliedByApiCallers() throws Exception {
        mvc.perform(post("/api/v1/integrations/candidates").header("Authorization", "Bearer lead-test-token")
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"idempotencyKey":"bad","expectedVersion":1,"taskIds":["WP-100"],
                     "profileId":"checks","commands":["anything"]}
                    """)).andExpect(status().isBadRequest());
    }
}
