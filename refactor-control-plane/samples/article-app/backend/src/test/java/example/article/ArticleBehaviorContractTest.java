package example.article;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest @AutoConfigureMockMvc
class ArticleBehaviorContractTest {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper json;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) throws Exception { ArticleDatabaseContractTest.database(registry); }
    @BeforeEach void ownership() {
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE LOWER(table_name)='article_blogs'",Integer.class)>0,"Ownership schema must exist");
        for(int i=1;i<=2;i++) {
            String owner=i==1?"alice":"bob";
            jdbc.update("INSERT INTO article_blogs(id,owner_subject,blog_url) SELECT ?,?,? WHERE NOT EXISTS(SELECT 1 FROM article_blogs WHERE id=?)",1000+i,owner,owner,1000+i);
            jdbc.update("INSERT INTO article_menus(id,blog_id,menu_name) SELECT ?,?,'articles' WHERE NOT EXISTS(SELECT 1 FROM article_menus WHERE id=?)",2000+i,1000+i,2000+i);
        }
    }
    @Test void requiresCsrfAndBlogOwnershipForEveryManagementWrite() throws Exception {
        mvc.perform(post("/api/manage/articles").with(user("alice")).contentType("application/json").content(body(2001,"DRAFT",null))).andExpect(status().isForbidden());
        mvc.perform(post("/api/manage/articles").with(user("bob")).with(csrf()).contentType("application/json").content(body(2001,"DRAFT",null))).andExpect(status().isNotFound());
        var article=create("DRAFT");String id=article.path("id").asText();
        mvc.perform(get("/api/manage/articles/"+id).with(user("bob"))).andExpect(status().isNotFound());
        mvc.perform(put("/api/manage/articles/"+id).with(user("bob")).with(csrf()).contentType("application/json").content(body(2001,"PUBLISHED",1L))).andExpect(status().isNotFound());
        mvc.perform(delete("/api/manage/articles/"+id+"?expectedVersion=1").with(user("bob")).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(get("/api/articles/"+id)).andExpect(status().isNotFound());
    }
    @Test void publishesOnlyCleanHtmlAndKeepsStableRoutesAcrossEditsAndSoftDeletion() throws Exception {
        var article=create("PUBLISHED");String id=article.path("id").asText(),path=article.path("publicPath").asText();
        var published=json.readTree(mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertFalse(published.has("content"));String html=published.path("html").asText();
        assertTrue(html.contains("<strong>"));assertTrue(html.contains("/image.png"));assertFalse(html.contains("onerror"));assertFalse(html.contains("<script"));assertFalse(html.contains("javascript:"));
        var edit=json.createObjectNode().put("title","Renamed").put("content","<p>edited</p>").put("status","PUBLISHED").put("expectedVersion",1);
        var updated=json.readTree(mvc.perform(put("/api/manage/articles/"+id).with(user("alice")).with(csrf()).contentType("application/json").content(edit.toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(path,updated.path("publicPath").asText());
        mvc.perform(put("/api/manage/articles/"+id).with(user("alice")).with(csrf()).contentType("application/json").content(edit.toString())).andExpect(status().isConflict());
        edit.put("expectedVersion",2).put("menuId",2002);
        mvc.perform(put("/api/manage/articles/"+id).with(user("alice")).with(csrf()).contentType("application/json").content(edit.toString())).andExpect(status().isConflict());
        mvc.perform(delete("/api/manage/articles/"+id+"?expectedVersion=2").with(user("alice")).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get(path)).andExpect(status().isNotFound());
        mvc.perform(get("/api/manage/articles/"+id).with(user("alice"))).andExpect(status().isNotFound());
        assertEquals("<p>edited</p>",jdbc.queryForObject("SELECT content FROM modern_articles WHERE id=? AND deleted=TRUE",String.class,Long.parseLong(id)));
    }
    @Test void sameTitleGetsDistinctStableAddressesAndOwnerFieldsCannotBeSpoofed() throws Exception {
        var first=create("PUBLISHED");var second=create("PUBLISHED");assertNotEquals(first.path("publicPath"),second.path("publicPath"));
        var forged=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(body(2001,"DRAFT",null));forged.put("ownerSubject","bob");
        mvc.perform(post("/api/manage/articles").with(user("alice")).with(csrf()).contentType("application/json").content(forged.toString())).andExpect(status().isBadRequest());
    }
    @Test void deletedRowsReserveRoutesAndRejectStaleDeletionWithoutTouchingAssociations() throws Exception {
        var article=create("PUBLISHED");long id=article.path("id").asLong();String path=article.path("publicPath").asText();
        mvc.perform(delete("/api/manage/articles/"+id+"?expectedVersion=2").with(user("alice")).with(csrf())).andExpect(status().isConflict());
        mvc.perform(get(path)).andExpect(status().isOk());
        mvc.perform(delete("/api/manage/articles/"+id+"?expectedVersion=1").with(user("alice")).with(csrf())).andExpect(status().isNoContent());
        var owned=json.readTree(mvc.perform(get("/api/manage/articles").with(user("alice"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        for(var row:owned)assertNotEquals(id,row.path("id").asLong());
        var publicRows=json.readTree(mvc.perform(get("/api/articles")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        for(var row:publicRows)assertNotEquals(id,row.path("id").asLong());
        assertEquals(2001,jdbc.queryForObject("SELECT menu_id FROM modern_articles WHERE id=? AND deleted=TRUE",Long.class,id));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM article_menus WHERE id=2001",Integer.class));
        mvc.perform(get("/api/public"+path)).andExpect(status().isNotFound());
    }
    @Test void accepts255UnicodeCodepointsAndRejectsOversizedBodies() throws Exception {
        var body=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(body(2001,"DRAFT",null));body.put("title","🙂".repeat(255));
        mvc.perform(post("/api/manage/articles").with(user("alice")).with(csrf()).contentType("application/json").content(body.toString())).andExpect(status().isCreated());
        body.put("content","文".repeat(350000));
        mvc.perform(post("/api/manage/articles").with(user("alice")).with(csrf()).contentType("application/json").content(body.toString())).andExpect(status().isBadRequest());
    }
    @Test void publicPathsRequireExactSegmentsIncludingTrailingSpaces() throws Exception {
        var article=create("PUBLISHED");String path=article.path("publicPath").asText();
        for(String altered:new String[]{path+"%20",path.replace("/alice/","/alice%20/"),path.replace("/articles/","/articles%20/")}) {
            mvc.perform(get(java.net.URI.create(altered))).andExpect(status().isNotFound());
        }
        for(String altered:new String[]{path.replace("/alice/","/Alice/"),path.replace("/articles/","/Articles/"),path.replace("/article-","/Article-")}) {
            mvc.perform(get(java.net.URI.create(altered))).andExpect(status().isNotFound());
        }
    }
    @Test void keepsAnExistingLegacySlugAcrossTitleEditing() throws Exception {
        var article=create("PUBLISHED");long id=article.path("id").asLong();
        jdbc.update("UPDATE modern_articles SET slug='Cevik-Yazilim' WHERE id=?",id);
        String path="/alice/articles/Cevik-Yazilim";
        mvc.perform(get(path)).andExpect(status().isOk());
        var edit=json.createObjectNode().put("title","新标题").put("content","<p>original format</p>").put("status","PUBLISHED").put("expectedVersion",1);
        mvc.perform(put("/api/manage/articles/"+id).with(user("alice")).with(csrf()).contentType("application/json").content(edit.toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.publicPath").value(path));
        mvc.perform(get("/api/public"+path)).andExpect(status().isOk()).andExpect(jsonPath("$.title").value("新标题"));
    }
    JsonNode create(String status) throws Exception { return json.readTree(mvc.perform(post("/api/manage/articles").with(user("alice")).with(csrf()).contentType("application/json").content(body(2001,status,null))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()); }
    String body(long menu,String status,Long version) {var node=json.createObjectNode().put("menuId",menu).put("title","Same title").put("content","<p><strong>format</strong><img src='/image.png' onerror='bad()'><script>bad()</script><a href='javascript:bad()'>link</a></p>").put("status",status);if(version!=null)node.put("expectedVersion",version);return node.toString();}
}
