package io.refactorcontrolplane.governance;

import java.util.List;
import java.util.Set;

public record AuditReport(
        String projectId,
        List<TaskPackage> tasks,
        Set<String> eventTypes,
        List<ChangeEvent> timeline,
        Set<String> risks) {
    public AuditReport {
        tasks = List.copyOf(tasks);
        eventTypes = Set.copyOf(eventTypes);
        timeline = List.copyOf(timeline);
        risks = Set.copyOf(risks);
    }
}
