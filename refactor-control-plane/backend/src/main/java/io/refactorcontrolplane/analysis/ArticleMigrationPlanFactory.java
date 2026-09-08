package io.refactorcontrolplane.analysis;

import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ArticleMigrationPlanFactory {

    private ArticleMigrationPlanFactory() {
    }

    public static MigrationPlan create(String baseCommit) {
        List<PlanWorkPackage> tasks = List.of(
                task("WP-100", "Confirm article behavior and ADR", "ARCHITECT", Set.of(),
                        Set.of("docs/architecture", "docs/contracts"), 6, ".",
                        List.of("git diff --check -- docs/architecture docs/contracts")),
                task("WP-110", "Publish article API contract", "ENGINEERING_LEAD", Set.of("WP-100"),
                        Set.of("docs/contracts/articles.openapi.yaml"), 6, ".",
                        List.of("git diff --check -- docs/contracts/articles.openapi.yaml")),
                task("WP-120", "Implement Spring Boot article API", "DEVELOPER", Set.of("WP-110"),
                        Set.of("backend/src/main/java/io/refactorcontrolplane/article"), 16, "backend",
                        List.of("mvn.cmd verify")),
                task("WP-130", "Implement Vue 3 article workspace", "DEVELOPER", Set.of("WP-110"),
                        Set.of("frontend/src/article"), 16, "frontend",
                        List.of("npm.cmd test", "npm.cmd run build")),
                task("WP-140", "Create non-destructive schema migration", "DEVELOPER", Set.of("WP-110"),
                        Set.of("backend/src/main/resources/db/migration"), 8, "backend",
                        List.of("mvn.cmd verify")),
                task("WP-150", "Run parity and integration gates", "QA_LEAD",
                        Set.of("WP-120", "WP-130", "WP-140"), Set.of("evidence/article"), 10,
                        ".", List.of("mvn.cmd -f backend/pom.xml verify", "npm.cmd --prefix frontend test")),
                task("WP-160", "Business acceptance and merge proposal", "BUSINESS_APPROVER", Set.of("WP-150"),
                        Set.of("evidence/article/acceptance"), 4, ".",
                        List.of("git diff --check -- evidence/article/acceptance")));
        return new MigrationPlan(
                baseCommit,
                "flowchart LR\n  Vue[Vue 3] --> API[Spring Boot REST]\n  API --> DB[(Compatible MySQL schema)]",
                tasks,
                List.of("WP-100", "WP-110", "WP-120", "WP-150", "WP-160"),
                Map.of("implementation", List.of("WP-120", "WP-130", "WP-140")),
                "OpenAPI 3.1: GET/POST/PUT/DELETE /api/articles and GET /api/articles/{id}",
                "non-destructive expand-and-contract migration; existing article rows remain readable",
                Map.of("文章列表", "GET /api/articles + Vue ArticleList",
                        "新增文章", "POST /api/articles + Vue ArticleEditor",
                        "编辑文章", "PUT /api/articles/{id}",
                        "删除文章", "DELETE /api/articles/{id}"),
                Set.of("source-analysis", "api-contract", "unit-tests", "integration-tests",
                        "code-review", "business-acceptance"));
    }

    private static PlanWorkPackage task(String id, String title, String role, Set<String> dependencies,
            Set<String> paths, int hours, String workingDirectory, List<String> commands) {
        return new PlanWorkPackage(id, title, role, dependencies, paths, hours, workingDirectory, commands);
    }
}
