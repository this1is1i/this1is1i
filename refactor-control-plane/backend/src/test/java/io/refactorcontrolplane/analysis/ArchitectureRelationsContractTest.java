package io.refactorcontrolplane.analysis;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class ArchitectureRelationsContractTest {
    @TempDir Path directory;
    final ObjectMapper mapper = new ObjectMapper();
    @ParameterizedTest @ValueSource(strings={"server", "products/catalog/backend"})
    void configurationSchemaControllersViewsAndModuleEdgesHaveFixedObjectEvidence(String layout) throws Exception {
        git("init", "-b", "main");
        write("pom.xml", "<project><modules><module>" + layout + "</module></modules></project>");
        write(layout + "/pom.xml", "<project><artifactId>catalog</artifactId></project>");
        write(layout + "/src/main/resources/application.properties", "spring.datasource.url=jdbc:mysql://localhost/blog?password=private-secret\nspring.datasource.password=private-secret\n");
        write(layout + "/src/main/resources/schema.sql", "CREATE TABLE menus(id INT PRIMARY KEY); CREATE TABLE posts(id INT, menu_id INT, FOREIGN KEY(menu_id) REFERENCES menus(id));");
        write(layout + "/src/main/java/demo/PostController.java", "package demo; import demo.PostDao; @Controller class PostController { @RequestMapping(\"/posts\") String list() { return \"posts/list\"; } }");
        write(layout + "/src/main/java/demo/PostDao.java", "package demo; class PostDao {}");
        write(layout + "/src/main/webapp/WEB-INF/views/posts/list.jsp", "<h1>Posts</h1>");
        write(layout + "/src/main/webapp/WEB-INF/spring/mvc.xml", "<beans><bean class=\"org.springframework.web.servlet.view.InternalResourceViewResolver\"><property name=\"prefix\" value=\"/WEB-INF/views/\"/><property name=\"suffix\" value=\".jsp\"/></bean></beans>");
        git("add", "."); git("-c", "user.name=Test", "-c", "user.email=test@localhost", "-c", "commit.gpgsign=false", "commit", "-m", "fixture");
        var report = mapper.valueToTree(new LegacyProjectAnalyzer().analyze(directory));
        var relations = report.path("relations");
        assertTrue(relations.isArray() && relations.size() >= 5, report::toString);
        var kinds = new HashSet<String>(); relations.forEach(edge -> {
            kinds.add(edge.path("kind").asText()); assertFalse(edge.path("evidenceIds").isEmpty());
            edge.path("evidenceIds").forEach(id -> assertTrue(report.path("evidence").findValues("id").contains(id)));
        });
        assertTrue(kinds.containsAll(Set.of("MAVEN_MODULE", "DATABASE_CONFIGURATION", "SCHEMA_REFERENCE", "DECLARED_ROUTE", "VIEW_CANDIDATE", "JAVA_IMPORT")), kinds::toString);
        assertFalse(report.toString().contains("private-secret"));
        assertTrue(report.path("currentArchitectureMermaid").asText().contains("-->"));
        String before = report.toString(); write(layout + "/src/main/resources/schema.sql", "CREATE TABLE unrelated(id INT);");
        assertEquals(before, mapper.valueToTree(new LegacyProjectAnalyzer().analyze(directory)).toString());
    }
    @Test void malformedExternalConfigurationAndUnsupportedCodeRemainUnknown() {
        var snapshot = new GitAnalysisSnapshot("a".repeat(40), List.of(
                new GitAnalysisSnapshot.Entry("mvc.xml", "b".repeat(40), "<!DOCTYPE beans SYSTEM 'http://invalid/external'><beans/>", null),
                new GitAnalysisSnapshot.Entry("Controller.java", "c".repeat(40), "class Controller { String sample = \"@RequestMapping(\\\"/fake\\\")\"; }", null)));
        var report = mapper.valueToTree(new LegacyProjectAnalyzer().analyze(snapshot));
        assertTrue(report.path("findings").toString().contains("UNKNOWN"));
        assertTrue(report.path("relations").isArray());
        assertFalse(report.path("relations").toString().contains("/fake"));
    }
    void write(String name, String value) throws Exception { Path p = directory.resolve(name); Files.createDirectories(p.getParent()); Files.writeString(p, value, StandardCharsets.UTF_8); }
    @Test void pluginParametersAndSqlCommentsAreNotDeclarations() {
        var report=new LegacyProjectAnalyzer().analyze(new GitAnalysisSnapshot("a".repeat(40),List.of(
                new GitAnalysisSnapshot.Entry("pom.xml","b".repeat(40),"<project><build><plugins><plugin><configuration><module>admin</module></configuration></plugin></plugins></build></project>",null),
                new GitAnalysisSnapshot.Entry("admin/pom.xml","c".repeat(40),"<project/>",null),
                new GitAnalysisSnapshot.Entry("schema.sql","d".repeat(40),"-- CREATE TABLE retired(id INT);\nCREATE TABLE posts(id INT,\n-- legacy REFERENCES accounts(id)\nauthor_id INT);",null))));
        assertTrue(report.relations().stream().noneMatch(edge->edge.kind().equals("MAVEN_MODULE")||edge.to().equals("table:retired")||edge.to().equals("table:accounts")));
    }
    @Test void manyLiteralCandidatesAreBoundedAndReportedAsIncomplete() {
        String text="class A {"+"String f(){ return \"a\"; }".repeat(20000)+"}";
        var report=assertTimeoutPreemptively(java.time.Duration.ofSeconds(3),()->new LegacyProjectAnalyzer().analyze(
                new GitAnalysisSnapshot("a".repeat(40),List.of(new GitAnalysisSnapshot.Entry("A.java","b".repeat(40),text,null)))));
        assertTrue(report.findings().stream().anyMatch(f->f.kind().equals("UNKNOWN")&&f.statement().contains("budget")));
    }
    @ParameterizedTest @ValueSource(strings={"application.properties","schema.sql"})
    void unsuccessfulMatchesCannotEscapeTheComputationBudget(String path) {
        String content=path.endsWith("sql")?"CREATE TABLE posts (\n".repeat(20000):" ".repeat(1000000);
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),()->new LegacyProjectAnalyzer().analyze(
                new GitAnalysisSnapshot("a".repeat(40),List.of(new GitAnalysisSnapshot.Entry(path,"b".repeat(40),content,null)))));
    }
    String git(String... args) throws Exception {
        var command = new ArrayList<>(List.of("git", "-C", directory.toString())); command.addAll(List.of(args));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output); return output.trim();
    }
}
