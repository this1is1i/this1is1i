package io.refactorcontrolplane.api;

import io.refactorcontrolplane.analysis.LegacyProjectReport;
import io.refactorcontrolplane.analysis.MigrationPlan;

public record DemoProjectOverview(
        String projectId,
        LegacyProjectReport analysis,
        MigrationPlan plan,
        String mergePolicy,
        String dataBoundary) {
}
