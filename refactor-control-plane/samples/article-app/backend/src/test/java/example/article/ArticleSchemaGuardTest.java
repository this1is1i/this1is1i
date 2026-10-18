package example.article;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ArticleSchemaGuardTest {
    @Test void rejectsAnExistingUnscopedSchemaWithoutAssigningOwnersOrDeletingData() {
        var data=new DriverManagerDataSource("jdbc:h2:mem:old-unscoped;DB_CLOSE_DELAY=-1","sa","");
        var jdbc=new JdbcTemplate(data);
        jdbc.execute("CREATE TABLE modern_articles(id BIGINT PRIMARY KEY,title VARCHAR(255),content TEXT,status VARCHAR(20),updated_at TIMESTAMP)");
        jdbc.update("INSERT INTO modern_articles(id,title,content,status) VALUES(1,'keep','untouched','DRAFT')");
        new ApplicationContextRunner().withBean(JdbcTemplate.class,()->jdbc).withUserConfiguration(ArticleService.class).run(context->assertNotNull(context.getStartupFailure(),"Old schema must fail closed before serving data"));
        assertEquals("untouched",jdbc.queryForObject("SELECT content FROM modern_articles WHERE id=1",String.class));
    }
}
