package example.article;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest @AutoConfigureMockMvc
class ArticleSessionContractTest {
    @Autowired MockMvc mvc;
    @org.springframework.test.context.DynamicPropertySource static void database(org.springframework.test.context.DynamicPropertyRegistry registry) throws Exception { ArticleDatabaseContractTest.database(registry); }
    @Test void anonymousSessionReceivesCsrfWithoutReceivingPrivateManagementAccess() throws Exception {
        mvc.perform(get("/api/session")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.authenticated").value(false)).andExpect(jsonPath("$.csrfToken").isNotEmpty());
        mvc.perform(get("/api/manage/articles")).andExpect(status().isUnauthorized());
    }
}
