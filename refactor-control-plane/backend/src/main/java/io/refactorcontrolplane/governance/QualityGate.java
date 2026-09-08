package io.refactorcontrolplane.governance;

public enum QualityGate {
    CODE_REVIEW(ProjectRole.ENGINEERING_LEAD),
    UNIT_TEST(ProjectRole.QA_LEAD),
    INTEGRATION_TEST(ProjectRole.QA_LEAD),
    BUSINESS_ACCEPTANCE(ProjectRole.BUSINESS_APPROVER);

    private final ProjectRole approvingRole;

    QualityGate(ProjectRole approvingRole) {
        this.approvingRole = approvingRole;
    }

    public ProjectRole approvingRole() {
        return approvingRole;
    }
}
