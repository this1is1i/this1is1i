package example.article;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@EnabledIfSystemProperty(named="article.mysql.config",matches=".+")
class MySqlEngineContractTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ArticleService articles;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) throws Exception { ArticleDatabaseContractTest.database(registry); }

    @Test void usesTheRealMysqlInnoDbUtf8mb4SchemaAndManagedConnector() throws Exception {
        assertTrue(jdbc.queryForObject("SELECT VERSION()",String.class).startsWith("8.0."));
        assertEquals("InnoDB",jdbc.queryForObject("SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='modern_articles'",String.class));
        assertEquals("utf8mb4",jdbc.queryForObject("SELECT CHARACTER_SET_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='modern_articles' AND COLUMN_NAME='content'",String.class));
        assertEquals("longtext",jdbc.queryForObject("SELECT DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='modern_articles' AND COLUMN_NAME='content'",String.class));
        assertEquals(6,jdbc.queryForObject("SELECT DATETIME_PRECISION FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='modern_articles' AND COLUMN_NAME='updated_at'",Integer.class));
        try(var connection=jdbc.getDataSource().getConnection()) { assertTrue(connection.getMetaData().getDriverVersion().startsWith("mysql-connector-j-9.2.0")); }
    }

    @Test void rollsBackTargetWritesOnFailureUsingRealDatabaseTransactions() {
        int before=jdbc.queryForObject("SELECT COUNT(*) FROM modern_articles",Integer.class);
        var transaction=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        assertThrows(IllegalStateException.class,()->transaction.executeWithoutResult(status->{
            articles.create(new ArticleCommand("atomicity proof","should roll back",ArticleStatus.DRAFT));
            throw new IllegalStateException("Injected application failure");
        }));
        assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM modern_articles",Integer.class));
    }

    @Test void keepsFixedLegacyPostsInAnIndependentReadOnlySchemaByteEquivalent() throws Exception {
        var settings=new Properties();try(var reader=Files.newBufferedReader(Path.of(System.getProperty("article.mysql.config")),StandardCharsets.UTF_8)){settings.load(reader);}
        var source=new JdbcTemplate(new DriverManagerDataSource(settings.getProperty("article.fixture.source.url"),settings.getProperty("article.fixture.source.username"),settings.getProperty("article.fixture.source.password")));
        String query="SELECT Id,HEX(PostTitle),HEX(PostContent),HEX(RequestMapping),BlogMenuId,CAST(DateTime AS CHAR) FROM posts ORDER BY Id";
        var before=source.queryForList(query);assertFalse(before.isEmpty());
        assertNotEquals(source.queryForObject("SELECT DATABASE()",String.class),jdbc.queryForObject("SELECT DATABASE()",String.class));
        var article=articles.create(new ArticleCommand("isolated new article","new data",ArticleStatus.DRAFT));
        articles.update(article.id(),new ArticleCommand("edited new article","new data",ArticleStatus.PUBLISHED));
        assertThrows(DataAccessException.class,()->source.update("UPDATE posts SET PostTitle='must be denied' WHERE Id=16"));
        assertEquals(before,source.queryForList(query));
    }
}
