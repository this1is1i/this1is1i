package io.refactorcontrolplane.integration;

import io.refactorcontrolplane.ownership.RepositoryPathPolicy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class IntegrationConflictAnalyzer {

    private final RepositoryPathPolicy pathPolicy;

    public IntegrationConflictAnalyzer(RepositoryPathPolicy pathPolicy) {
        this.pathPolicy = pathPolicy;
    }

    public IntegrationReport analyze(List<ChangesetDescriptor> changesets) {
        List<IntegrationConflict> conflicts = new ArrayList<>();
        Set<String> affected = new LinkedHashSet<>();
        for (int left = 0; left < changesets.size(); left++) {
            for (int right = left + 1; right < changesets.size(); right++) {
                ChangesetDescriptor first = changesets.get(left);
                ChangesetDescriptor second = changesets.get(right);
                Set<String> pair = Set.of(first.taskId(), second.taskId());
                boolean pathConflict = first.changedPaths().stream().anyMatch(firstPath ->
                        second.changedPaths().stream().anyMatch(secondPath ->
                                pathPolicy.overlaps(pathPolicy.normalize(firstPath), pathPolicy.normalize(secondPath))));
                if (pathConflict) {
                    conflicts.add(new IntegrationConflict("PATH", pair,
                            "Overlapping repository path scopes"));
                    affected.addAll(pair);
                }
                if (first.apiContractVersion() != second.apiContractVersion()) {
                    conflicts.add(new IntegrationConflict("API_CONTRACT", pair,
                            "API versions " + first.apiContractVersion() + " and " + second.apiContractVersion()));
                    affected.addAll(pair);
                }
                if (first.schemaVersion() != second.schemaVersion()) {
                    conflicts.add(new IntegrationConflict("DB_SCHEMA", pair,
                            "Schema versions " + first.schemaVersion() + " and " + second.schemaVersion()));
                    affected.addAll(pair);
                }
            }
        }
        List<String> order = changesets.stream().map(ChangesetDescriptor::taskId).toList();
        return new IntegrationReport(conflicts, affected, order, false);
    }
}
