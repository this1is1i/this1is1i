package io.refactorcontrolplane.analysis;

import java.io.StringReader;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

final class MavenSnapshotDetector implements SnapshotDetector {
    @Override public void inspect(GitAnalysisSnapshot.Entry entry, AnalysisCollector result) {
        if (!entry.path().equals("pom.xml") && !entry.path().endsWith("/pom.xml")) return;
        result.technology("Maven", entry); result.module("Maven module", entry);
        Element project;
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth", "128");
            factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder(); builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
            project = builder.parse(new InputSource(new StringReader(entry.text()))).getDocumentElement();
            if (!project.getTagName().equals("project")) throw new IllegalArgumentException("Not a Maven project element");
        } catch (Exception exception) {
            result.finding("UNKNOWN", "manifest", "Maven manifest is malformed or uses unsupported external declarations", entry); return;
        }
        Map<String, String> properties = new LinkedHashMap<>();
        children(child(project, "properties")).forEach(property -> properties.put(property.getTagName(), property.getTextContent().trim()));
        Element parent = child(project, "parent");
        boolean bootParent = text(parent, "groupId").equals("org.springframework.boot")
                && text(parent, "artifactId").equals("spring-boot-starter-parent");
        String java = "";
        for (Element plugin : children(child(child(project, "build"), "plugins"))) {
            if (!text(plugin, "artifactId").equals("maven-compiler-plugin")
                    || (!text(plugin, "groupId").isEmpty() && !text(plugin, "groupId").equals("org.apache.maven.plugins"))) continue;
            Element config = child(plugin, "configuration");
            java = text(config, "release");
            if (java.isEmpty()) java = properties.getOrDefault("maven.compiler.release", "");
            if (java.isEmpty()) java = text(config, "source");
            break;
        }
        if (java.isEmpty()) java = properties.getOrDefault("maven.compiler.release", properties.getOrDefault("maven.compiler.source", ""));
        if (java.isEmpty() && bootParent) java = properties.getOrDefault("java.version", "");
        java = resolve(java, properties);
        if (java.matches("(?:1\\.)?\\d{1,2}")) {
            int major = Integer.parseInt(java.startsWith("1.") ? java.substring(2) : java);
            result.technology("Java " + major, entry);
            if (major < 21) {
                result.risk("Declared Java target " + major + " differs from platform baseline 21; verify migration compatibility", entry);
                result.upgrade("Evaluate Java " + major + " → Java 21 against compilation and behavior tests", entry);
            }
        } else result.finding("UNKNOWN", "runtime", "Java target is absent, inherited or unresolved", entry);

        if (bootParent) {
            technologyVersion("Spring Boot", resolve(text(parent, "version"), properties), entry, result);
        }
        for (Element dependency : children(child(project, "dependencies"))) {
            if (!dependency.getTagName().equals("dependency")) continue;
            String group = resolve(text(dependency, "groupId"), properties);
            String artifact = resolve(text(dependency, "artifactId"), properties);
            String version = resolve(text(dependency, "version"), properties);
            result.finding("FACT", "dependency", "Declared dependency " + group + ":" + artifact
                    + (version.isBlank() ? " (version inherited or unresolved)" : ":" + version), entry);
            if (group.equals("org.springframework") && artifact.equals("spring-webmvc")) {
                technologyVersion("Spring MVC", version, entry, result);
            }
            if (group.equals("org.springframework") && artifact.equals("spring-jdbc")) result.technology("Spring JDBC", entry);
            if ((group.equals("mysql") && artifact.equals("mysql-connector-java"))
                    || (group.equals("com.mysql") && artifact.equals("mysql-connector-j"))) {
                result.technology("MySQL JDBC driver", entry);
                result.finding("UNKNOWN", "database", "A JDBC driver declaration does not establish the deployed MySQL server version", entry);
            }
        }
        if (child(project, "profiles") != null) result.finding("UNKNOWN", "coverage", "Maven profile activation was not evaluated", entry);
        if (parent != null) result.finding("UNKNOWN", "coverage", "Inherited Maven configuration was not resolved; no build or network access performed", entry);
    }
    private static void technologyVersion(String name, String version, GitAnalysisSnapshot.Entry entry, AnalysisCollector result) {
        result.technology(version.isBlank() ? name : name + " " + version, entry);
        if (version.isBlank()) result.finding("UNKNOWN", "dependency", name + " version is inherited or unresolved", entry);
    }
    private static String resolve(String value, Map<String, String> properties) {
        Set<String> visited = new HashSet<>();
        while (value.matches("\\$\\{[^}]+}")) {
            if (!visited.add(value)) return "";
            value = properties.getOrDefault(value.substring(2, value.length() - 1), "");
        }
        return value.contains("${") ? "" : value;
    }
    private static Element child(Element parent, String name) {
        return children(parent).stream().filter(element -> element.getTagName().equals(name)).findFirst().orElse(null);
    }
    private static String text(Element parent, String name) {
        Element found = child(parent, name); return found == null ? "" : found.getTextContent().trim();
    }
    private static List<Element> children(Element parent) {
        if (parent == null) return List.of();
        List<Element> elements = new ArrayList<>();
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element) elements.add(element);
        }
        return elements;
    }
}
