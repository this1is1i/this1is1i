package io.refactorcontrolplane.governance;

public record ManualMergeProposal(
        String taskId,
        String branch,
        String commitSha,
        boolean humanApprovalRequired,
        boolean automaticallyMergeMain) {
}
