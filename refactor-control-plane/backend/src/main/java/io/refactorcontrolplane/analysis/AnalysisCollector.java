package io.refactorcontrolplane.analysis;

import java.util.*;

final class AnalysisCollector {
    private final GitAnalysisSnapshot snapshot;
    final Set<String> stack = new LinkedHashSet<>();
    private final List<Evidence> evidence = new ArrayList<>();
    private final List<AnalysisFinding> findings = new ArrayList<>();
    private final Map<String, Set<String>> modules = new TreeMap<>();
    private final List<String> risks = new ArrayList<>();
    private final List<String> upgrades = new ArrayList<>();
    private final List<ArchitectureRelation> relations = new ArrayList<>();

    AnalysisCollector(GitAnalysisSnapshot snapshot) { this.snapshot = snapshot; }
    void technology(String label, GitAnalysisSnapshot.Entry entry) {
        stack.add(label); finding("FACT", "technology", "Declared or present: " + label, entry);
    }
    void module(String name, GitAnalysisSnapshot.Entry entry) {
        int slash = entry.path().lastIndexOf('/');
        String directory = slash < 0 ? "." : entry.path().substring(0, slash);
        modules.computeIfAbsent(name + "\0" + directory, ignored -> new LinkedHashSet<>()).add(entry.path());
    }
    void finding(String kind, String category, String statement, GitAnalysisSnapshot.Entry entry) {
        List<String> ids = List.of();
        if (entry != null) {
            String id = "evidence-" + (evidence.size() + 1);
            evidence.add(new Evidence(statement, entry.path(), "SOURCE", id, snapshot.commit(), entry.blobId()));
            ids = List.of(id);
        }
        findings.add(new AnalysisFinding(kind, category, statement, ids));
    }
    void risk(String statement, GitAnalysisSnapshot.Entry entry) {
        if (!risks.contains(statement)) risks.add(statement);
        finding("INFERENCE", "risk", statement, entry);
    }
    void upgrade(String statement, GitAnalysisSnapshot.Entry entry) {
        if (!upgrades.contains(statement)) upgrades.add(statement);
        finding("INFERENCE", "upgrade", statement, entry);
    }
    void relation(String kind, String from, String to, String confidence, String explanation, GitAnalysisSnapshot.Entry... entries) {
        if (relations.size() >= 500) return;
        var ids = new ArrayList<String>();
        for (var entry : entries) {
            String id = "evidence-" + (evidence.size() + 1);
            evidence.add(new Evidence(explanation, entry.path(), "SOURCE", id, snapshot.commit(), entry.blobId())); ids.add(id);
        }
        relations.add(new ArchitectureRelation(kind, from, to, confidence, explanation, ids));
    }
    LegacyProjectReport report() {
        var moduleList = modules.entrySet().stream().map(entry -> {
            String[] parts = entry.getKey().split("\0");
            return new ModuleInsight(parts[0], parts[1], entry.getValue().size());
        }).toList();
        StringBuilder mermaid = new StringBuilder("flowchart LR\n  %% Detected inventory; no unverified call edges\n");
        int index = 0;
        for (String label : stack) mermaid.append("  T").append(index++).append("[\"")
                .append(label.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
                        .replace(">", "&gt;").replace("\r", " ").replace("\n", " "))
                .append("\"]\n");
        if (stack.isEmpty()) mermaid.append("  U[\"Unknown technology stack\"]\n");
        var nodes = new LinkedHashMap<String, String>();
        for (var relation : relations) {
            for (String endpoint : List.of(relation.from(), relation.to())) {
                if (!nodes.containsKey(endpoint)) {
                    String id = "R" + nodes.size(); nodes.put(endpoint, id);
                    mermaid.append("  ").append(id).append("[\"").append(escape(endpoint)).append("\"]\n");
                }
            }
            mermaid.append("  ").append(nodes.get(relation.from())).append(" -->|\"").append(relation.kind())
                    .append(" / ").append(relation.confidence()).append("\"| ").append(nodes.get(relation.to())).append("\n");
        }
        return new LegacyProjectReport(snapshot.commit(), stack, moduleList, evidence, mermaid.toString(), risks, upgrades, findings, relations);
    }
    private static String escape(String text) { return text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", " ").replace("\r", " "); }
}
