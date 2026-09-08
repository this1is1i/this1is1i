package io.refactorcontrolplane.governance;

public enum SemanticAsset {
    API_CONTRACT("ContractPublished"),
    DB_SCHEMA("SchemaChanged"),
    ARCHITECTURE_DECISION("DecisionAccepted");

    private final String eventType;

    SemanticAsset(String eventType) {
        this.eventType = eventType;
    }

    public String eventType() {
        return eventType;
    }
}
