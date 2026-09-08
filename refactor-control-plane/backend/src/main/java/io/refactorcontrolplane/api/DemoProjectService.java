package io.refactorcontrolplane.api;

import io.refactorcontrolplane.analysis.ArticleMigrationPlanFactory;
import io.refactorcontrolplane.analysis.LegacyProjectAnalyzer;
import io.refactorcontrolplane.analysis.LegacyProjectReport;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class DemoProjectService {

    private final LegacyProjectReport analysis;

    public DemoProjectService(
            @Value("${control-plane.demo.repository:../samples/workspaces/springmvc-blog}") String repository) {
        this.analysis = new LegacyProjectAnalyzer().analyze(Path.of(repository));
    }

    public DemoProjectOverview overview() {
        return new DemoProjectOverview(
                "legacy-blog",
                analysis,
                ArticleMigrationPlanFactory.create(analysis.snapshotCommit()),
                "HUMAN_ONLY",
                "SOURCE_CODE_STAYS_INTERNAL");
    }
}
