CREATE TABLE IF NOT EXISTS integration_record (
    id VARCHAR(36) PRIMARY KEY,
    kind VARCHAR(24) NOT NULL,
    operation_key VARCHAR(160) NOT NULL UNIQUE,
    actor VARCHAR(255) NOT NULL,
    request_json CLOB NOT NULL,
    payload CLOB NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS integration_member (
    candidate_id VARCHAR(36) NOT NULL REFERENCES integration_record(id),
    task_id VARCHAR(100) NOT NULL,
    PRIMARY KEY (candidate_id, task_id)
);
