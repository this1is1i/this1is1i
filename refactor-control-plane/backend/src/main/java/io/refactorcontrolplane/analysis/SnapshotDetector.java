package io.refactorcontrolplane.analysis;

interface SnapshotDetector {
    void inspect(GitAnalysisSnapshot.Entry entry, AnalysisCollector result);
}
