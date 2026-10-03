package io.refactorcontrolplane.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.refactorcontrolplane.persistence.AuditEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "control-plane.identity-file=classpath:review-identities.json")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class GovernanceIdentityApiContractTest {
    @Autowired MockMvc mvc;
    @Autowired AuditEventRepository audit;

    @Test
    void governanceRequiresAServerAuthenticatedPrincipal() throws Exception {
        mvc.perform(post("/api/v1/governance/commands").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action":"assign_task","taskId":"WP-100","assignee":"developer",
                                 "idempotencyKey":"unauthenticated","expectedVersion":1}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerIssuesATaskCredentialWithoutPersistingIt() throws Exception {
        String response = mvc.perform(post("/api/v1/governance/commands")
                        .header("Authorization", "Bearer manager-test-token")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"action":"assign_task","taskId":"WP-100","assignee":"developer",
                                 "idempotencyKey":"assign-developer","expectedVersion":1}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.taskToken").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String token = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).path("taskToken").asText();
        assertFalse(audit.findAll().toString().contains(token));
        assertFalse(audit.findAll().toString().contains("manager-test-token"));

        mvc.perform(post("/api/v1/governance/commands")
                        .header("Authorization", "Bearer developer-test-token")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"action":"start_review","taskId":"WP-100","commitSha":"deadbeef",
                                 "idempotencyKey":"self-review","expectedVersion":1}
                                """))
                .andExpect(status().isForbidden());
    }
}
