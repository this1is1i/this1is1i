package example.article;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ArticleApiContractTest {
    @org.junit.jupiter.api.BeforeEach void ownership() { OwnedArticleFixture.seed(jdbc); }
    @org.springframework.test.context.DynamicPropertySource
    static void database(org.springframework.test.context.DynamicPropertyRegistry registry) throws Exception { ArticleDatabaseContractTest.database(registry); }

    @Autowired
    private MockMvc mvc;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Test void usesTheExplicitlyRequestedDatabaseForTheRestContract() throws Exception {
        try(var connection=jdbc.getDataSource().getConnection()) {
            org.junit.jupiter.api.Assertions.assertEquals(System.getProperty("article.mysql.config")==null?"H2":"MySQL",connection.getMetaData().getDatabaseProductName());
        }
    }

    @Test
    void createsAndReadsAnArticleThroughTheVersionedRestContract() throws Exception {
        String location = mvc.perform(post("/api/manage/articles").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("tester")).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"menuId":201,"title":"Migration proof","content":"Vue 3 + Spring Boot","status":"DRAFT"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.title").value("Migration proof"))
                .andReturn().getResponse().getHeader("Location");

        mvc.perform(get("/api/manage/articles").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("tester")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.title == 'Migration proof')]").exists());

        mvc.perform(put(location).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("tester")).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":1,"title":"Migration verified","content":"persisted","status":"PUBLISHED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));

        mvc.perform(delete(location+"?expectedVersion=2").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("tester")).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())).andExpect(status().isNoContent());
        mvc.perform(get(location).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("tester"))).andExpect(status().isNotFound());
    }

    @Test
    void preservesLegacyLengthTitlesAndRejectsLongerOnes() throws Exception {
        mvc.perform(post("/api/manage/articles").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("tester")).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"menuId\":201,\"title\":\"" + "文".repeat(255) + "\",\"content\":\"legacy\",\"status\":\"DRAFT\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/manage/articles").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("tester")).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"menuId\":201,\"title\":\"" + "文".repeat(256) + "\",\"content\":\"legacy\",\"status\":\"DRAFT\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsArticleWithoutARequiredTitle() throws Exception {
        mvc.perform(post("/api/manage/articles").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("tester")).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"missing title\",\"status\":\"DRAFT\"}"))
                .andExpect(status().isBadRequest());
    }
}
