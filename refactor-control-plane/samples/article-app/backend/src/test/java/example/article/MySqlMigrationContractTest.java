package example.article;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.ZoneId;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.*;

@SpringBootTest
@EnabledIfSystemProperty(named="article.mysql.config",matches=".+")
class MySqlMigrationContractTest {
    @Autowired JdbcTemplate target;@Autowired ArticleService articles;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) throws Exception {ArticleDatabaseContractTest.database(registry);}
    @Test void migratesTheSevenPinnedPostsThroughTheLocalCommandAndPreservesDeletionOnRetry() throws Exception {
        var settings=new Properties();try(var in=Files.newBufferedReader(Path.of(System.getProperty("article.mysql.config")),StandardCharsets.UTF_8)){settings.load(in);}
        var source=new JdbcTemplate(new DriverManagerDataSource(settings.getProperty("article.fixture.source.url"),settings.getProperty("article.fixture.source.username"),settings.getProperty("article.fixture.source.password")));
        String query="SELECT Id,HEX(PostTitle),HEX(PostContent),HEX(RequestMapping),BlogMenuId,CAST(DateTime AS CHAR) FROM posts ORDER BY Id";
        var before=source.queryForList(query);assertEquals(7,before.size());
        var sameSchemaAlias=new JdbcTemplate(new DriverManagerDataSource(settings.getProperty("spring.datasource.url")+"&databaseTerm=SCHEMA",settings.getProperty("spring.datasource.username"),settings.getProperty("spring.datasource.password")));
        assertThrows(IllegalArgumentException.class,()->new LegacyArticleMigration(sameSchemaAlias,target,Set.of("migration-author")),"Connection metadata options cannot bypass schema isolation");
        var tool=new LegacyArticleMigration(source,target,Set.of("migration-author","other-author"));
        var policy=new LegacyArticleMigration.Policy(ZoneId.of("Europe/Istanbul"),ArticleStatus.PUBLISHED,Map.of(3L,"migration-author",8L,"other-author"));
        var failedTarget=new JdbcTemplate(new DriverManagerDataSource(settings.getProperty("article.fixture.failure.url"),settings.getProperty("spring.datasource.username"),settings.getProperty("spring.datasource.password")));
        var failedTool=new LegacyArticleMigration(source,failedTarget,Set.of("migration-author","other-author"));
        var failurePreview=failedTool.preview(policy);
        assertThrows(RuntimeException.class,()->failedTool.apply(policy,failurePreview.fingerprint(),"operator"));
        for(String table:List.of("modern_articles","article_blogs","article_menus","legacy_post_import","article_migration_run"))
            assertEquals(0,failedTarget.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class),"Real MySQL rollback: "+table);
        assertNull(failedTarget.queryForObject("SELECT source_identity FROM article_migration_lock WHERE id=1",String.class));
        var preview=tool.preview(policy);assertTrue(preview.issues().isEmpty(),preview::toString);assertEquals(7,preview.pending());
        var priorTargets=target.queryForList("SELECT * FROM modern_articles ORDER BY id");
        var sourceAlias=new JdbcTemplate(new DriverManagerDataSource(settings.getProperty("article.fixture.source.url")+"&databaseTerm=SCHEMA",settings.getProperty("article.fixture.source.username"),settings.getProperty("article.fixture.source.password")));
        assertEquals(preview.fingerprint(),new LegacyArticleMigration(sourceAlias,target,Set.of("migration-author","other-author")).preview(policy).fingerprint());
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->tool.apply(policy,preview.fingerprint(),"operator"));var b=pool.submit(()->tool.apply(policy,preview.fingerprint(),"operator"));
            assertEquals(7,a.get().imported()+b.get().imported());
        }
        assertEquals(7,target.queryForObject("SELECT COUNT(*) FROM legacy_post_import",Integer.class));
        assertEquals(1,target.queryForObject("SELECT COUNT(*) FROM article_migration_run",Integer.class));
        for(var prior:priorTargets)assertEquals(prior,target.queryForMap("SELECT * FROM modern_articles WHERE id=?",prior.get("id")));
        var mappingEvidence=new ArrayList<Map<String,Object>>();
        for(var row:source.queryForList("SELECT p.Id,p.PostTitle,p.PostContent,p.RequestMapping,p.DateTime,b.Id AS blog_id,b.BlogUrl,m.MenuName FROM posts p JOIN blogsmenus bm ON bm.Id=p.BlogMenuId JOIN blogs b ON b.Id=bm.BlogId JOIN menus m ON m.Id=bm.MenuId ORDER BY p.Id")) {
            long id=target.queryForObject("SELECT article_id FROM legacy_post_import WHERE legacy_id=?",Long.class,row.get("Id"));
            var article=articles.find(policy.owners().get(((Number)row.get("blog_id")).longValue()),id);
            assertEquals(row.get("PostTitle"),article.title());assertEquals(row.get("PostContent"),article.content());
            assertTrue(article.publicPath().endsWith(row.get("RequestMapping").toString()));
            assertEquals(id,articles.published(row.get("BlogUrl").toString(),row.get("MenuName").toString(),row.get("RequestMapping").toString()).id());
            var local=source.queryForObject("SELECT DateTime FROM posts WHERE Id=?",(r,n)->r.getObject(1,java.time.LocalDateTime.class),row.get("Id"));
            assertEquals(local.atZone(policy.sourceZone()).toInstant().truncatedTo(java.time.temporal.ChronoUnit.MICROS),article.updatedAt());
            mappingEvidence.add(Map.of("legacyId",row.get("Id"),"articleId",id,"sourceBlogId",row.get("blog_id"),"targetMenuId",article.menuId(),
                "publicPath",article.publicPath(),"sourceLocalTime",local.toString(),"targetUtcTime",article.updatedAt().toString(),
                "sourceFingerprint",target.queryForObject("SELECT source_fingerprint FROM legacy_post_import WHERE legacy_id=?",String.class,row.get("Id"))));
        }
        var one=articles.findAll("migration-author").getFirst();articles.delete("migration-author",one.id(),one.version());
        assertFalse(tool.preview(policy).issues().isEmpty());assertThrows(IllegalStateException.class,()->tool.apply(policy,preview.fingerprint(),"operator"));
        assertEquals(7,target.queryForObject("SELECT COUNT(*) FROM legacy_post_import",Integer.class));
        assertEquals(before,source.queryForList(query));
        assertThrows(org.springframework.dao.DataAccessException.class,()->source.update("UPDATE posts SET PostTitle='denied' WHERE Id=16"));
        Path accounts=Files.createTempFile("migration-accounts-",".json"),config=Files.createTempFile("migration-config-",".json");
        try {
            var json=new com.fasterxml.jackson.databind.ObjectMapper();
            String hash="{bcrypt}"+new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode("fixture-only");
            Files.writeString(accounts,json.writeValueAsString(List.of(Map.of("subject","migration-author","passwordHash",hash),Map.of("subject","other-author","passwordHash",hash))),StandardCharsets.UTF_8);
            var data=new LinkedHashMap<String,Object>();
            data.put("source",Map.of("url",settings.getProperty("article.fixture.source.url"),"username",settings.getProperty("article.fixture.source.username"),"password",settings.getProperty("article.fixture.source.password")));
            data.put("target",Map.of("url",settings.getProperty("spring.datasource.url"),"username",settings.getProperty("spring.datasource.username"),"password",settings.getProperty("spring.datasource.password")));
            data.put("accountsFile",accounts.toString());data.put("sourceZone","Europe/Istanbul");data.put("targetStatus","PUBLISHED");data.put("owners",Map.of("3","migration-author","8","other-author"));data.put("operator","operator");
            Files.writeString(config,json.writeValueAsString(data),StandardCharsets.UTF_8);
            var cli=ArticleMigrationCommand.execute(new String[]{"preview",config.toString()});assertNotNull(cli);assertEquals(7,cli.sourceCount());assertFalse(cli.issues().isEmpty());
            String javaExe=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
            var process=new ProcessBuilder(javaExe,"-cp",System.getProperty("surefire.test.class.path"),ArticleApplication.class.getName(),"--migration","preview",config.toString()).redirectErrorStream(true).start();
            var output=java.util.concurrent.CompletableFuture.supplyAsync(()->{try{return new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);}catch(Exception e){throw new RuntimeException(e);}});
            try {assertTrue(process.waitFor(30,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,process.exitValue());assertEquals(7,json.readTree(output.get()).path("sourceCount").asInt());}
            finally {if(process.isAlive())process.destroyForcibly();}
        } finally {Files.deleteIfExists(config);Files.deleteIfExists(accounts);}
        Files.writeString(Path.of(System.getProperty("article.mysql.config")).getParent().resolve("migration-mappings.json"),
            new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("mappings",mappingEvidence,"sourceZone","Europe/Istanbul","targetStatus","PUBLISHED",
                "rollbackVerified",true,"sourceUnchanged",true,"concurrentImported",7,"successAudits",1,"deletedTargetPreserved",true)),StandardCharsets.UTF_8);
    }
}
