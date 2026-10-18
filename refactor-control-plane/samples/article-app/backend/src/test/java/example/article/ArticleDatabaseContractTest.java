package example.article;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class ArticleDatabaseContractTest {
    @Autowired ArticleService articles;
    @org.junit.jupiter.api.BeforeEach void ownership() { OwnedArticleFixture.seed(jdbc); }
    @Autowired JdbcTemplate jdbc;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) throws Exception {
        String file=System.getProperty("article.mysql.config");
        if(file!=null) {
            var settings=new Properties();try(var reader=Files.newBufferedReader(Path.of(file),StandardCharsets.UTF_8)){settings.load(reader);}
            settings.stringPropertyNames().forEach(key->registry.add(key,()->settings.getProperty(key)));
        }
    }
    @Test void creationResponseExactlyMatchesTheStoredMicrosecondInstant() {
        var created=articles.create("tester",OwnedArticleFixture.command("precision","body",ArticleStatus.DRAFT));
        assertEquals(created.updatedAt(),articles.find("tester",created.id()).updatedAt());
        assertEquals(0,created.updatedAt().getNano()%1000,"Storage and API promise microsecond precision");
    }
    @Test void roundTripsUnicodeAndHtmlLargerThanMysqlTextWithoutTruncation() {
        String content="<p>中文🙂</p>".repeat(8000);
        var created=articles.create("tester",OwnedArticleFixture.command("标题🙂",content,ArticleStatus.DRAFT));
        assertEquals(content,articles.find("tester",created.id()).content());
    }
    @Test void storesDatesBeyond2038AsUtcRegardlessOfTheJvmDefaultZone() {
        Instant future=Instant.parse("2070-04-03T02:01:00.123456Z");
        var created=articles.create("tester",OwnedArticleFixture.command("future","body",ArticleStatus.DRAFT));
        jdbc.update(connection->{
            var statement=connection.prepareStatement("UPDATE modern_articles SET updated_at=? WHERE id=?");
            statement.setTimestamp(1,Timestamp.from(future),Calendar.getInstance(TimeZone.getTimeZone("UTC")));
            statement.setLong(2,created.id());return statement;
        });
        assertEquals(future,articles.find("tester",created.id()).updatedAt());
    }
}
