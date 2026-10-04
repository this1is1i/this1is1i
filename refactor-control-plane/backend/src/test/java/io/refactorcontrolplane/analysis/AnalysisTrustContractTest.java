package io.refactorcontrolplane.analysis;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnalysisTrustContractTest {
    @TempDir Path repository;
    final LegacyProjectAnalyzer analyzer = new LegacyProjectAnalyzer();

    @BeforeEach void initialize() throws Exception {
        git("init", "-b", "main");
        git("config", "user.name", "Analysis Test");
        git("config", "user.email", "analysis@example.invalid");
    }

    @Test void analysisIgnoresDirtyStagedDeletedAndUntrackedFiles() throws Exception {
        write("pom.xml", pom("7")); write("src/main/views/index.jsp", "<html>committed</html>"); commit();
        var original = analyzer.analyze(repository);
        write("pom.xml", pom("21")); git("add", "pom.xml");
        Files.delete(repository.resolve("src/main/views/index.jsp"));
        write("injected/package.json", "{\"dependencies\":{\"vue\":\"3.0.0\"}}");
        assertEquals(original, analyzer.analyze(repository), "analysis must read committed Git objects exclusively");
    }

    @Test void modernMavenDoesNotInheritTheDemoArchitectureOrRisks() throws Exception {
        write("pom.xml", """
                <project><parent><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-parent</artifactId>
                <version>3.5.0</version></parent><properties><java.version>21</java.version></properties>
                <dependencies><dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId>
                </dependency></dependencies></project>
                """); commit();
        var report = analyzer.analyze(repository);
        assertTrue(report.technologyStack().contains("Java 21"));
        assertTrue(report.technologyStack().contains("Spring Boot 3.5.0"));
        assertFalse(report.currentArchitectureMermaid().contains("MySQL"));
        assertFalse(report.currentArchitectureMermaid().contains("JSP"));
        assertTrue(report.risks().isEmpty(), report.risks()::toString);
        assertTrue(report.modules().stream().noneMatch(module -> module.path().equals("not-detected")));
    }

    @Test void supportsNodeManifestWithoutInventingJavaFacts() throws Exception {
        write("web/package.json", "{\"dependencies\":{\"vue\":\"^3.5.0\"},\"devDependencies\":{\"vite\":\"^8.0.0\"}}"); commit();
        var report = assertDoesNotThrow(() -> analyzer.analyze(repository));
        assertTrue(report.technologyStack().contains("Vue"));
        assertTrue(report.technologyStack().contains("npm"));
        assertFalse(report.currentArchitectureMermaid().contains("Spring"));
    }

    @Test void unsupportedInputProducesExplicitUnknowns() throws Exception {
        write("README.md", "Only documentation; no detected stack."); commit();
        var report = assertDoesNotThrow(() -> analyzer.analyze(repository));
        assertTrue(report.technologyStack().isEmpty());
        var json = new ObjectMapper().valueToTree(report);
        assertTrue(json.path("findings").findValuesAsText("kind").contains("UNKNOWN"));
        assertTrue(report.risks().isEmpty());
    }

    @Test void xmlCommentsAndDependencyManagementAreNotActiveDependencies() throws Exception {
        write("pom.xml", """
                <project><!-- <java-version>1.7</java-version><org.springframework-version>4.1</org.springframework-version> -->
                <dependencyManagement><dependencies><dependency><groupId>mysql</groupId><artifactId>mysql-connector-java</artifactId>
                <version>5.1</version></dependency></dependencies></dependencyManagement></project>
                """); commit();
        var stack = analyzer.analyze(repository).technologyStack();
        assertFalse(stack.contains("Java 7"));
        assertFalse(stack.contains("Spring MVC 4.1"));
        assertFalse(stack.contains("MySQL"));
    }

    @Test void allModulesCarryCommitAndBlobEvidence() throws Exception {
        write("api/pom.xml", pom("21")); write("worker/pom.xml", pom("17")); commit();
        var report = analyzer.analyze(repository);
        assertTrue(report.technologyStack().containsAll(List.of("Java 21", "Java 17")));
        var json = new ObjectMapper().valueToTree(report);
        for (var evidence : json.path("evidence")) {
            assertEquals(report.snapshotCommit(), evidence.path("snapshotCommit").asText());
            assertEquals(git("rev-parse", report.snapshotCommit() + ":" + evidence.path("path").asText()),
                    evidence.path("blobId").asText());
        }
        assertTrue(json.path("findings").findValuesAsText("kind").contains("FACT"));
    }

    @Test void linkedWorktreesAreValidAnalysisInputs() throws Exception {
        write("pom.xml", pom("21")); commit();
        Path linked = repository.resolveSibling(repository.getFileName() + "-linked");
        git("worktree", "add", "--detach", linked.toString(), "HEAD");
        try { assertEquals(analyzer.analyze(repository), assertDoesNotThrow(() -> analyzer.analyze(linked))); }
        finally { git("worktree", "remove", linked.toString()); }
    }

    @Test void malformedManifestIsUnknownRatherThanAStartupFailure() throws Exception {
        write("pom.xml", "<project><broken>"); commit();
        var report = assertDoesNotThrow(() -> analyzer.analyze(repository));
        assertTrue(new ObjectMapper().valueToTree(report).path("findings").findValuesAsText("kind").contains("UNKNOWN"));
    }

    @Test void capturedObjectsRemainStableAfterHeadMovesAndReplacementRefsChange() throws Exception {
        write("pom.xml", pom("7")); commit();
        var snapshot = GitAnalysisSnapshot.capture(repository);
        var original = analyzer.analyze(snapshot);
        String originalBlob = git("rev-parse", "HEAD:pom.xml");
        write("pom.xml", pom("21")); commit();
        String newerBlob = git("rev-parse", "HEAD:pom.xml");
        git("replace", newerBlob, originalBlob);
        assertEquals(original, analyzer.analyze(snapshot));
        assertTrue(analyzer.analyze(repository).technologyStack().contains("Java 21"), "replace refs must not redefine snapshot object identity");
        assertNotEquals(original.snapshotCommit(), analyzer.analyze(repository).snapshotCommit());
    }

    @Test void readsLargeUtf8BlobsAndReportsSkippedOversizedContent() throws Exception {
        write("资料/package.json", "{\"description\":\"" + "中".repeat(30000) + "\",\"dependencies\":{\"vue\":\"3\"}}");
        write("oversized/pom.xml", "x".repeat(1024 * 1024 + 1)); commit();
        var report = analyzer.analyze(repository);
        assertTrue(report.technologyStack().contains("Vue"));
        assertTrue(report.evidence().stream().anyMatch(item -> item.path().equals("资料/package.json")));
        assertTrue(report.findings().stream().anyMatch(item -> item.kind().equals("UNKNOWN") && item.statement().contains("size limit")));
    }

    @Test void externalXmlEntitiesAndPropertyCyclesCannotProvideFacts() throws Exception {
        write("pom.xml", """
                <!DOCTYPE project [<!ENTITY target SYSTEM "file:///not-allowed">]>
                <project><properties><java.version>&target;</java.version></properties></project>
                """);
        write("cycle/pom.xml", "<project><properties><java.version>${a}</java.version><a>${java.version}</a></properties></project>");
        commit();
        var report = assertDoesNotThrow(() -> analyzer.analyze(repository));
        assertFalse(report.technologyStack().stream().anyMatch(item -> item.startsWith("Java ")));
        assertTrue(report.findings().stream().anyMatch(item -> item.kind().equals("UNKNOWN") && item.category().equals("manifest")));
    }

    @Test void malformedNpmVersionIsNotReportedAsADependencyFact() throws Exception {
        write("package.json", "{\"dependencies\":{\"vue\":null}}"); commit();
        var report = analyzer.analyze(repository);
        assertFalse(report.technologyStack().contains("Vue"));
        assertTrue(report.findings().stream().anyMatch(item -> item.kind().equals("UNKNOWN") && item.category().equals("manifest")));
    }

    static String pom(String version) {
        return "<project><properties><maven.compiler.release>" + version + "</maven.compiler.release></properties></project>";
    }

    @Test void explicitCompilerTargetOverridesAnUnreferencedJavaProperty() throws Exception {
        write("pom.xml", """
                <project><properties><java.version>8</java.version></properties><build><plugins>
                <plugin><artifactId>maven-compiler-plugin</artifactId><configuration><release>21</release></configuration></plugin>
                </plugins></build></project>
                """); commit();
        var report = analyzer.analyze(repository);
        assertTrue(report.technologyStack().contains("Java 21"));
        assertFalse(report.technologyStack().contains("Java 8"));
        assertTrue(report.risks().isEmpty());
    }

    @Test void deeplyNestedXmlIsUnknownWithoutCrashingAnalysis() throws Exception {
        write("pom.xml", "<project><properties><java.version>" + "<x>".repeat(12000)
                + "21" + "</x>".repeat(12000) + "</java.version></properties></project>"); commit();
        var report = assertDoesNotThrow(() -> analyzer.analyze(repository));
        assertTrue(report.findings().stream().anyMatch(item -> item.kind().equals("UNKNOWN") && item.category().equals("manifest")));
    }

    @Test void missingPromisedObjectsDoNotTriggerNetworkFetch() throws Exception {
        write("pom.xml", pom("21")); commit();
        String blob = git("rev-parse", "HEAD:pom.xml");
        Path object = repository.resolve(".git/objects/" + blob.substring(0, 2) + "/" + blob.substring(2));
        Files.move(object, repository.resolve("saved-object"));
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> { requests.incrementAndGet(); exchange.sendResponseHeaders(404, -1); exchange.close(); });
        server.start();
        try {
            git("config", "remote.origin.url", "http://127.0.0.1:" + server.getAddress().getPort() + "/missing");
            git("config", "remote.origin.promisor", "true");
            assertThrows(RuntimeException.class, () -> analyzer.analyze(repository));
            assertEquals(0, requests.get(), "read-only analysis must not lazily fetch missing objects");
        } finally { server.stop(0); }
    }

    @Test void submodulesAndSymbolicLinksAreLimitationsNotSourceBlobs() throws Exception {
        write("README.md", "fixture"); write("target.txt", "../../outside/pom.xml"); commit();
        String head = git("rev-parse", "HEAD");
        String linkBlob = git("rev-parse", "HEAD:target.txt");
        git("update-index", "--add", "--cacheinfo", "160000," + head + ",vendor");
        git("update-index", "--add", "--cacheinfo", "120000," + linkBlob + ",linked/pom.xml");
        git("commit", "-m", "links");
        var report = analyzer.analyze(repository);
        assertTrue(report.technologyStack().isEmpty());
        Evidence submodule = report.evidence().stream().filter(item -> item.path().equals("vendor")).findFirst().orElseThrow();
        assertNull(submodule.blobId(), "a submodule commit must not be presented as a blob");
        assertTrue(submodule.claim().contains(head));
        assertTrue(report.evidence().stream().anyMatch(item -> item.path().equals("linked/pom.xml") && item.blobId().equals(linkBlob)));
    }

    @Test void jsonTrailingTokensAndDuplicateKeysAreRejectedBeforePublishingFacts() throws Exception {
        write("trailing/package.json", "{\"dependencies\":{\"vue\":\"3\"}} true");
        write("duplicate/package.json", "{\"dependencies\":{\"react\":\"18\"},\"dependencies\":{\"vue\":\"3\"}}"); commit();
        var report = analyzer.analyze(repository);
        assertFalse(report.technologyStack().contains("Vue"));
        assertEquals(2, report.findings().stream().filter(item -> item.kind().equals("UNKNOWN") && item.category().equals("manifest")).count());
    }

    @Test void emptyNpmVersionRangeRemainsAValidDeclaration() throws Exception {
        write("package.json", "{\"dependencies\":{\"vue\":\"\"}}"); commit();
        assertTrue(analyzer.analyze(repository).technologyStack().contains("Vue"));
    }
    void write(String name, String text) throws Exception {
        Path file = repository.resolve(name); Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }
    void commit() throws Exception { git("add", "."); git("commit", "-m", "fixture"); }
    String git(String... args) throws Exception {
        var command = new ArrayList<>(List.of("git", "-C", repository.toString())); command.addAll(List.of(args));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertEquals(0, process.waitFor(), output); return output;
    }
}
