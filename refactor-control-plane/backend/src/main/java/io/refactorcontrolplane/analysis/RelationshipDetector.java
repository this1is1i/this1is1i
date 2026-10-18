package io.refactorcontrolplane.analysis;

import java.io.StringReader;
import java.util.*;
import java.util.regex.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

/** Bounded declaration analysis, never a claim about runtime dispatch or a complete call graph. */
final class RelationshipDetector {
    record Resolver(String module, String prefix, String suffix, GitAnalysisSnapshot.Entry entry) {}
    void inspect(GitAnalysisSnapshot snapshot, AnalysisCollector result) {
        try { inspectBounded(snapshot,result,new Budget()); }
        catch (BudgetExceeded exhausted) { result.finding("UNKNOWN","relationships","Relationship candidate budget exhausted; remaining declarations require review",null); }
    }
    private static final class BudgetExceeded extends RuntimeException {}
    private static final class Budget { int left=10000; void tick(){ if (--left<0) throw new BudgetExceeded(); } }
    private void inspectBounded(GitAnalysisSnapshot snapshot, AnalysisCollector result, Budget budget) {
        var entries = snapshot.entries().stream().filter(e -> e.text() != null && e.limitation() == null).toList();
        var byPath = new HashMap<String, GitAnalysisSnapshot.Entry>(); entries.forEach(e -> byPath.put(e.path(), e));
        var modules = entries.stream().filter(e -> e.path().equals("pom.xml") || e.path().endsWith("/pom.xml"))
                .map(e -> e.path().equals("pom.xml") ? "" : e.path().substring(0, e.path().length() - 7))
                .sorted(Comparator.comparingInt(String::length).reversed()).toList();
        var resolvers = new ArrayList<Resolver>();
        var classes = new HashMap<String, List<GitAnalysisSnapshot.Entry>>();
        var masks = new HashMap<String, BitSet>();
        for (var entry : entries) {
            budget.tick();
            if (entry.path().endsWith(".java") || entry.path().endsWith(".sql")) masks.put(entry.path(),codeMask(entry.text(),entry.path().endsWith(".sql")));
            if (entry.path().endsWith(".xml")) xml(entry, byPath, modules, resolvers, result,budget);
            if (entry.path().endsWith(".properties") || entry.path().endsWith(".yml") || entry.path().endsWith(".yaml")) {
                var jdbcPattern=Pattern.compile("^[ \\t]*[A-Za-z0-9_.-]+[ \\t]*[:=][ \\t]*jdbc:([A-Za-z0-9]+):");
                var lines=entry.text().lines().iterator();
                while(lines.hasNext()) { budget.tick(); var jdbc=jdbcPattern.matcher(lines.next());
                    if(jdbc.find()) result.relation("DATABASE_CONFIGURATION", entry.path(), "jdbc:" + jdbc.group(1), "FACT",
                        "Configuration declares a JDBC driver protocol; credentials, address and deployed server version are not reported", entry); }
            }
            if (entry.path().endsWith(".java")) {
                var pkg = Pattern.compile("\\bpackage\\s+([A-Za-z0-9_.]+)\\s*;").matcher(entry.text());
                if (pkg.find() && masks.get(entry.path()).get(pkg.start())) {
                    String name = entry.path().substring(entry.path().lastIndexOf('/') + 1).replaceFirst("\\.java$", "");
                    classes.computeIfAbsent(pkg.group(1) + "." + name, ignored -> new ArrayList<>()).add(entry);
                }
            }
            if (entry.path().endsWith(".sql")) {
                var tables = Pattern.compile("(?i)\\bCREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?[`\"]?([A-Za-z_][A-Za-z0-9_]*)[`\"]?\\s*\\(").matcher(entry.text());
                while (tables.find()) {
                    budget.tick(); if (!masks.get(entry.path()).get(tables.start())) continue;
                    int end=entry.text().indexOf(';',tables.end());
                    if(end<0) { result.finding("UNKNOWN","schema","Unterminated SQL declaration; remaining schema requires review",entry); break; }
                    result.relation("SCHEMA_TABLE", entry.path(), "table:" + tables.group(1), "INFERENCE", "SQL declaration names a table; deployed schema is unverified", entry);
                    var refs = Pattern.compile("(?i)\\bREFERENCES\\s+[`\"]?([A-Za-z_][A-Za-z0-9_]*)").matcher(entry.text()).region(tables.end(),end);
                    while (refs.find()) { budget.tick(); if (masks.get(entry.path()).get(refs.start()))
                        result.relation("SCHEMA_REFERENCE", "table:" + tables.group(1), "table:" + refs.group(1), "INFERENCE", "SQL contains a REFERENCES declaration; execution and enforcement are unverified", entry); }
                    tables.region(end+1,entry.text().length());
                }
            }
        }
        for (var entry : entries) if (entry.path().endsWith(".java")) {
            String source = entry.text();
            var imports = Pattern.compile("\\bimport\\s+([A-Za-z0-9_.]+)\\s*;").matcher(source);
            while (imports.find()) {
                budget.tick();
                var candidates = classes.getOrDefault(imports.group(1), List.of());
                if (masks.get(entry.path()).get(imports.start()) && candidates.size() == 1)
                    result.relation("JAVA_IMPORT", entry.path(), candidates.getFirst().path(), "FACT", "Source imports the declared type; this is not a runtime call edge", entry, candidates.getFirst());
            }
            var routes = Pattern.compile("@(?:Request|Get|Post|Put|Delete|Patch)Mapping\\s*\\(\\s*(?:(?:value|path)\\s*=\\s*)?\"([^\"\\r\\n]{1,200})\"").matcher(source);
            while (routes.find()) { budget.tick(); if (masks.get(entry.path()).get(routes.start()))
                result.relation("DECLARED_ROUTE", entry.path(), "route:" + routes.group(1), "INFERENCE", "Mapping annotation declares this path fragment; class/method composition and security require review", entry); }
            var views = Pattern.compile("\\breturn\\s+\"([A-Za-z0-9_/-]{1,200})\"\\s*;").matcher(source);
            while (views.find()) { budget.tick(); if (masks.get(entry.path()).get(views.start())) {
                for (var resolver : resolvers) {
                    budget.tick();
                    if (!resolver.module().equals(module(entry.path(), modules))) continue;
                    String suffix = resolver.prefix() + views.group(1) + resolver.suffix();
                    for (var target : entries) { budget.tick(); if (target.path().endsWith(suffix) && module(target.path(), modules).equals(resolver.module()))
                        result.relation("VIEW_CANDIDATE", entry.path(), target.path(), "INFERENCE", "Literal return and configured view resolver match a source view; runtime rendering is unverified", entry, resolver.entry(), target); }
                }
            } }
        }
        result.finding("UNKNOWN", "relationships", "Relationships cover bounded literal declarations only (up to 500); dynamic routes, SQL, YAML structure, injection, profile activation and runtime call graphs require review", null);
    }
    private void xml(GitAnalysisSnapshot.Entry entry, Map<String, GitAnalysisSnapshot.Entry> paths, List<String> modules,
            List<Resolver> resolvers, AnalysisCollector result, Budget budget) {
        Document document;
        try {
            var factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth", "128");
            factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder(); builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
            document = builder.parse(new InputSource(new StringReader(entry.text())));
        } catch (Exception ex) { result.finding("UNKNOWN", "configuration", "XML relationships unavailable: malformed, external or excessive-depth configuration", entry); return; }
        String directory = entry.path().contains("/") ? entry.path().substring(0, entry.path().lastIndexOf('/') + 1) : "";
        if (entry.path().equals("pom.xml") || entry.path().endsWith("/pom.xml")) {
            var declared = document.getElementsByTagNameNS("*", "module");
            for (int i = 0; i < declared.getLength(); i++) {
                budget.tick();
                Node container=declared.item(i).getParentNode(), owner=container.getParentNode();
                if (!"modules".equals(container.getLocalName()) || owner==null) continue;
                boolean root=owner==document.getDocumentElement() && "project".equals(owner.getLocalName());
                boolean profile="profile".equals(owner.getLocalName()) && owner.getParentNode()!=null
                        && "profiles".equals(owner.getParentNode().getLocalName()) && owner.getParentNode().getParentNode()==document.getDocumentElement();
                if (!root && !profile) continue;
                String name = declared.item(i).getTextContent().strip();
                if (!name.matches("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*")) continue;
                var child = paths.get(directory + name + "/pom.xml");
                if (child != null) result.relation("MAVEN_MODULE", entry.path(), child.path(), "FACT", "Maven declares a module whose manifest exists in this snapshot; profile activation is unverified", entry, child);
                else result.finding("UNKNOWN", "relationships", "Declared Maven module manifest is not available in the snapshot", entry);
            }
        }
        var beans = document.getElementsByTagNameNS("*", "bean");
        for (int i = 0; i < beans.getLength(); i++) {
            budget.tick();
            var bean = (Element) beans.item(i); var properties = bean.getElementsByTagNameNS("*", "property");
            String prefix = null, suffix = null;
            for (int j = 0; j < properties.getLength(); j++) {
                budget.tick();
                if (properties.item(j).getParentNode()!=bean) continue;
                var property = (Element) properties.item(j); String value = property.getAttribute("value");
                if (property.getAttribute("name").equals("prefix")) prefix = value;
                if (property.getAttribute("name").equals("suffix")) suffix = value;
                var jdbc = Pattern.compile("^jdbc:([A-Za-z0-9]+):").matcher(value);
                if (jdbc.find()) result.relation("DATABASE_CONFIGURATION", entry.path(), "jdbc:" + jdbc.group(1), "FACT", "XML declares a JDBC driver protocol; connection details are omitted", entry);
            }
            if (bean.getAttribute("class").equals("org.springframework.web.servlet.view.InternalResourceViewResolver")
                    && prefix != null && prefix.matches("/[A-Za-z0-9_/-]+/") && suffix != null && suffix.matches("\\.[A-Za-z0-9]+"))
                resolvers.add(new Resolver(module(entry.path(), modules), prefix, suffix, entry));
        }
    }
    private static String module(String path, List<String> modules) { return modules.stream().filter(path::startsWith).findFirst().orElse(""); }
    private static BitSet codeMask(String source, boolean sql) {
        var mask=new BitSet(source.length());
        boolean line = false, block = false; char quote = 0;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i), next = i + 1 < source.length() ? source.charAt(i + 1) : 0;
            if (line) { if (c == '\n' || c == '\r') line = false; continue; }
            if (block) { if (c == '*' && next == '/') { block = false; i++; } continue; }
            if (quote != 0) { if (c == '\\') i++; else if (c == quote) quote = 0; continue; }
            if ((!sql && c == '/' && next == '/') || (sql && ((c=='-' && next=='-') || c=='#'))) { line = true; if(c!='#')i++; }
            else if (c == '/' && next == '*') { block = true; i++; }
            else if (c == '\'' || c == '"') quote = c;
            else mask.set(i);
        }
        return mask;
    }
}
