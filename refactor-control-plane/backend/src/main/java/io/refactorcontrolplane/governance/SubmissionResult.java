package io.refactorcontrolplane.governance;

import java.util.List;

public record SubmissionResult(boolean accepted, List<String> unauthorizedPaths) {
    public SubmissionResult {
        unauthorizedPaths = List.copyOf(unauthorizedPaths);
    }
}
