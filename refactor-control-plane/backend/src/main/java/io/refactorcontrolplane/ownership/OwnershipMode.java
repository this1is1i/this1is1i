package io.refactorcontrolplane.ownership;

public enum OwnershipMode {
    READ,
    SHARED_WRITE,
    EXCLUSIVE_WRITE;

    public boolean isCompatibleWith(OwnershipMode other) {
        if (this == READ || other == READ) {
            return true;
        }
        return this == SHARED_WRITE && other == SHARED_WRITE;
    }
}
