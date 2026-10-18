package io.refactorcontrolplane.project;

import static io.refactorcontrolplane.api.ConfiguredProjectApiContractTest.*;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.*;

class RequirementPlanContractTest {
    @TempDir Path directory;
    @Test void approvedPlanCanDeclareExactRequirementTaskLinksAndRejectUnknownTasks() throws Exception {
        var repo = Files.createDirectories(directory.resolve("source")); git(repo, "init", "-b", "main");
        Files.createDirectory(repo.resolve("src")); Files.writeString(repo.resolve("src/pom.xml"), "<project/>", StandardCharsets.UTF_8);
        git(repo, "add", "."); commit(repo); var plan = configuration(repo, git(repo, "rev-parse", "HEAD"), "src");
        plan.putArray("requirements").addObject().put("id", "REQ-1").put("statement", "保留目录查询行为").putArray("taskIds").add("CAT-1");
        var file = directory.resolve("project.json"); Files.writeString(file, plan.toString(), StandardCharsets.UTF_8);
        var db = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).addScript("schema.sql").addScript("db/migration/V3__integration_candidates.sql").build();
        try {
            var source = assertDoesNotThrow(() -> new ConfiguredProjectService(file.toString(), new JdbcTemplate(db), new ObjectMapper()));
            assertEquals("REQ-1", new ObjectMapper().valueToTree(source.definition()).path("requirements").get(0).path("id").asText());
            ((ArrayNode) plan.path("requirements").get(0).path("taskIds")).add("UNKNOWN");
            assertThrows(IllegalArgumentException.class, () -> ProjectPlanValidator.validate(new ObjectMapper().treeToValue(plan, ProjectDefinition.class), repo));
            plan.remove("requirements");
            assertFalse(new ObjectMapper().valueToTree(new ObjectMapper().treeToValue(plan, ProjectDefinition.class)).has("requirements"));
        } finally { db.shutdown(); }
    }
}
