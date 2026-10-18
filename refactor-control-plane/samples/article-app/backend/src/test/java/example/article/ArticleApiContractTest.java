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
        String location = mvc.perform(post("/api/articles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Migration proof","content":"Vue 3 + Spring Boot","status":"DRAFT"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.title").value("Migration proof"))
                .andReturn().getResponse().getHeader("Location");

        mvc.perform(get("/api/articles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.title == 'Migration proof')]").exists());

        mvc.perform(put(location)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Migration verified","content":"persisted","status":"PUBLISHED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));

        mvc.perform(delete(location)).andExpect(status().isNoContent());
        mvc.perform(get(location)).andExpect(status().isNotFound());
    }

    @Test
    void preservesLegacyLengthTitlesAndRejectsLongerOnes() throws Exception {
        mvc.perform(post("/api/articles").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + "文".repeat(255) + "\",\"content\":\"legacy\",\"status\":\"DRAFT\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/articles").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + "文".repeat(256) + "\",\"content\":\"legacy\",\"status\":\"DRAFT\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsArticleWithoutARequiredTitle() throws Exception {
        mvc.perform(post("/api/articles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"missing title\",\"status\":\"DRAFT\"}"))
                .andExpect(status().isBadRequest());
    }
}
