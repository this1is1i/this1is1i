package io.refactorcontrolplane.analysis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public final class LegacyProjectAnalyzer {

    public LegacyProjectReport analyze(Path repository) {
        Path root = repository.toAbsolutePath().normalize();
        if (!Files.isDirectory(root.resolve(".git"))) {
            throw new IllegalArgumentException("Not a Git repository: " + root);
        }
        List<Path> files = sourceFiles(root);
        Path pom = files.stream().filter(path -> path.getFileName().toString().equals("pom.xml"))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("No Maven pom.xml found"));
        String pomText = read(pom);

        Set<String> stack = new LinkedHashSet<>();
        if (pomText.contains("<java-version>1.7</java-version>")
                || pomText.contains("<maven.compiler.source>1.7</maven.compiler.source>")) stack.add("Java 7");
        if (pomText.contains("<org.springframework-version>4.1")) stack.add("Spring MVC 4.1");
        if (files.stream().anyMatch(path -> path.toString().endsWith(".jsp"))) stack.add("JSP");
        if (pomText.contains("spring-jdbc")) stack.add("Spring JDBC");
        if (pomText.contains("mysql-connector-java")) stack.add("MySQL");
        if (files.stream().anyMatch(path -> path.getFileName().toString().contains("jquery"))) stack.add("jQuery");

        List<ModuleInsight> modules = List.of(
                module(root, files, "Controllers", "/controllers/"),
                module(root, files, "Data Access", "/dao/"),
                module(root, files, "Domain Models", "/models/"),
                module(root, files, "JSP Views", "/WEB-INF/views/"));
        List<Evidence> evidence = new ArrayList<>();
        evidence.add(new Evidence("Build and dependency versions", relative(root, pom), "SOURCE"));
        first(files, "/controllers/", ".java").ifPresent(path ->
                evidence.add(new Evidence("MVC controller layer", relative(root, path), "SOURCE")));
        first(files, "/WEB-INF/views/", ".jsp").ifPresent(path ->
                evidence.add(new Evidence("Server-rendered view layer", relative(root, path), "SOURCE")));
        first(files, "/resources/db/", ".sql").ifPresent(path ->
                evidence.add(new Evidence("Database schema", relative(root, path), "SOURCE")));

        return new LegacyProjectReport(
                gitHead(root),
                stack,
                modules,
                evidence,
                "flowchart LR\n  JSP[JSP + jQuery] --> MVC[Spring MVC Controllers]\n"
                        + "  MVC --> DAO[Spring JDBC DAO]\n  DAO --> DB[(MySQL 5.6)]",
                List.of("Unsupported Java runtime", "Framework security support ended",
                        "JSP and controller coupling", "Schema changes lack migrations"),
                List.of("Java 7 → Java 21", "Spring MVC 4.1 → Spring Boot 3",
                        "JSP/jQuery → Vue 3", "Ad-hoc SQL → versioned database migrations"));
    }

    private static List<Path> sourceFiles(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> !path.toString().contains("\\.git\\"))
                    .filter(path -> !path.toString().contains("/.git/"))
                    .filter(path -> !path.toString().contains("\\target\\"))
                    .filter(path -> !path.toString().contains("/target/"))
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot scan repository " + root, exception);
        }
    }

    private static ModuleInsight module(Path root, List<Path> files, String name, String marker) {
        List<Path> matching = files.stream().filter(path -> unix(path).contains(marker)).toList();
        String path = matching.isEmpty() ? "not-detected" : relative(root, matching.getFirst().getParent());
        return new ModuleInsight(name, path, matching.size());
    }

    private static java.util.Optional<Path> first(List<Path> files, String marker, String extension) {
        return files.stream().filter(path -> unix(path).contains(marker) && unix(path).endsWith(extension)).findFirst();
    }

    private static String unix(Path path) { return path.toString().replace('\\', '/'); }
    private static String relative(Path root, Path path) { return unix(root.relativize(path)); }

    private static String read(Path path) {
        try { return Files.readString(path, StandardCharsets.UTF_8); }
        catch (IOException exception) { throw new IllegalStateException("Cannot read " + path, exception); }
    }

    private static String gitHead(Path root) {
        ProcessBuilder builder = new ProcessBuilder("git", "rev-parse", "HEAD");
        builder.directory(root.toFile()).redirectErrorStream(true);
        try {
            Process process = builder.start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("git rev-parse timed out");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0) throw new IllegalStateException("git rev-parse failed: " + output);
            return output;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot execute git", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reading Git snapshot", exception);
        }
    }
}
