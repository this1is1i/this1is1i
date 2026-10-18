package io.refactorcontrolplane.executor;

import io.refactorcontrolplane.executor.ExecutionProtocol.*;

public interface TaskExecutor {
    Result start(Request request);
    /** null means unknown/not found; it is never permission to submit a new job. */
    Result query(Request request);
}
