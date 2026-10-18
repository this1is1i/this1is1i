package io.refactorcontrolplane.article;

import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** Compatibility for the historical demo only; configured projects never initialize business tables. */
@Configuration
@ConditionalOnProperty(name="control-plane.project.mode",havingValue="demo",matchIfMissing=true)
public class DemoBusinessSchema {
    @Bean @DependsOnDatabaseInitialization
    InitializingBean initializeDemoBusinessTables(DataSource dataSource) {
        return () -> new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__create_modern_articles.sql"),
                new ClassPathResource("db/migration/V2__legacy_post_import.sql")).execute(dataSource);
    }
}
