package io.refactorcontrolplane.governance;

import io.refactorcontrolplane.workpackage.WorkPackageStatus;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class TaskPackage {

    private final String id;
    private final String baseCommit;
    private final long contextVersion;
    private final long contractVersion;
    private final long schemaVersion;
    private final long adrVersion;
    private final Set<String> allowedPaths;
    private final Set<String> forbiddenPaths;
    private final String workingDirectory;
    private final List<String> acceptanceCommands;
    private WorkPackageStatus status;

    public TaskPackage(String id, String baseCommit, long contextVersion, long contractVersion,
            long schemaVersion, long adrVersion, Set<String> allowedPaths, Set<String> forbiddenPaths,
            String workingDirectory, List<String> acceptanceCommands, WorkPackageStatus status) {
        this.id = requireText(id, "id");
        this.baseCommit = requireText(baseCommit, "baseCommit");
        this.contextVersion = positive(contextVersion, "contextVersion");
        this.contractVersion = positive(contractVersion, "contractVersion");
        this.schemaVersion = positive(schemaVersion, "schemaVersion");
        this.adrVersion = positive(adrVersion, "adrVersion");
        this.allowedPaths = Set.copyOf(allowedPaths);
        this.forbiddenPaths = Set.copyOf(forbiddenPaths);
        this.workingDirectory = requireText(workingDirectory, "workingDirectory");
        this.acceptanceCommands = List.copyOf(acceptanceCommands);
        this.status = Objects.requireNonNull(status, "status must not be null");
        if (this.allowedPaths.isEmpty() || this.acceptanceCommands.isEmpty()) {
            throw new IllegalArgumentException("task must declare allowed paths and acceptance commands");
        }
    }

    public synchronized void moveTo(WorkPackageStatus target) {
        Objects.requireNonNull(target, "target must not be null");
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException("Illegal task transition: " + status + " -> " + target);
        }
        status = target;
    }

    public String id() { return id; }
    public String baseCommit() { return baseCommit; }
    public long contextVersion() { return contextVersion; }
    public long contractVersion() { return contractVersion; }
    public long schemaVersion() { return schemaVersion; }
    public long adrVersion() { return adrVersion; }
    public Set<String> allowedPaths() { return allowedPaths; }
    public Set<String> forbiddenPaths() { return forbiddenPaths; }
    public String workingDirectory() { return workingDirectory; }
    public List<String> acceptanceCommands() { return acceptanceCommands; }
    public synchronized WorkPackageStatus status() { return status; }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }

    private static long positive(long value, String field) {
        if (value < 1) throw new IllegalArgumentException(field + " must be positive");
        return value;
    }
}
