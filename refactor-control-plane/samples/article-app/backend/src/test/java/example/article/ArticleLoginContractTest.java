package example.article;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest @AutoConfigureMockMvc
class ArticleLoginContractTest {
    @Autowired MockMvc mvc; @Autowired ObjectMapper json;
    @DynamicPropertySource static void configuration(DynamicPropertyRegistry registry) throws Exception {
        ArticleDatabaseContractTest.database(registry);
        Path accounts=Files.createTempFile("article-test-accounts-",".json");accounts.toFile().deleteOnExit();
        String hash=new BCryptPasswordEncoder(4).encode("test-only-password");
        Files.writeString(accounts,"[{\"subject\":\"alice\",\"passwordHash\":\"{bcrypt}"+hash+"\"}]",StandardCharsets.UTF_8);
        registry.add("article.accounts-file",accounts::toString);
    }
    @Test void authenticatesRealHashRotatesCsrfAndEndsSessionOnLogout() throws Exception {
        var initial=mvc.perform(get("/api/session")).andExpect(status().isOk()).andReturn();
        var session=(MockHttpSession)initial.getRequest().getSession(false);
        String csrf=json.readTree(initial.getResponse().getContentAsString()).path("csrfToken").asText();
        mvc.perform(post("/api/session/login").session(session).param("username","alice").param("password","test-only-password")).andExpect(status().isForbidden());
        mvc.perform(post("/api/session/login").session(session).header("X-CSRF-TOKEN",csrf).param("username","alice").param("password","wrong")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/session/login").session(session).header("X-CSRF-TOKEN",csrf).param("username","alice").param("password","test-only-password")).andExpect(status().isNoContent());
        var current=mvc.perform(get("/api/session").session(session)).andExpect(jsonPath("$.subject").value("alice")).andExpect(jsonPath("$.authenticated").value(true)).andReturn();
        String rotated=json.readTree(current.getResponse().getContentAsString()).path("csrfToken").asText();
        mvc.perform(post("/api/session/logout").session(session).header("X-CSRF-TOKEN",csrf)).andExpect(status().isForbidden());
        mvc.perform(post("/api/session/logout").session(session).header("X-CSRF-TOKEN",rotated)).andExpect(status().isNoContent());
        assertTrue(session.isInvalid());
        mvc.perform(get("/api/manage/articles")).andExpect(status().isUnauthorized());
    }
}
