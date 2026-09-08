package io.refactorcontrolplane.ownership;

import java.util.Locale;

public final class RepositoryPathPolicy {

    private final boolean caseSensitive;

    private RepositoryPathPolicy(boolean caseSensitive) {
        this.caseSensitive = caseSensitive;
    }

    public static RepositoryPathPolicy caseSensitive() {
        return new RepositoryPathPolicy(true);
    }

    public static RepositoryPathPolicy caseInsensitive() {
        return new RepositoryPathPolicy(false);
    }

    public boolean overlaps(String first, String second) {
        String firstKey = comparisonKey(first);
        String secondKey = comparisonKey(second);
        return firstKey.equals(".")
                || secondKey.equals(".")
                || firstKey.equals(secondKey)
                || firstKey.startsWith(secondKey + "/")
                || secondKey.startsWith(firstKey + "/");
    }

    public boolean covers(String parentPath, String candidatePath) {
        String parentKey = comparisonKey(PathLeaseRegistry.normalize(parentPath));
        String candidateKey = comparisonKey(PathLeaseRegistry.normalize(candidatePath));
        return parentKey.equals(".")
                || parentKey.equals(candidateKey)
                || candidateKey.startsWith(parentKey + "/");
    }

    public String normalize(String path) {
        return PathLeaseRegistry.normalize(path);
    }

    private String comparisonKey(String path) {
        return caseSensitive ? path : path.toLowerCase(Locale.ROOT);
    }
}
