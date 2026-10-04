package io.refactorcontrolplane.analysis;

public record Evidence(String claim, String path, String sourceType, String id, String snapshotCommit, String blobId) {
}
