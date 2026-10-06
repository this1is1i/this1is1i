-- H2 reference implementation. The old posts table is read-only and is never created here.
ALTER TABLE modern_articles ALTER COLUMN title VARCHAR(255) NOT NULL;
ALTER TABLE modern_articles ALTER COLUMN updated_at TIMESTAMP(9) NOT NULL DEFAULT CURRENT_TIMESTAMP;

CREATE TABLE IF NOT EXISTS article_migration_lock (id INTEGER PRIMARY KEY);
INSERT INTO article_migration_lock (id) SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM article_migration_lock WHERE id = 1);

CREATE TABLE IF NOT EXISTS legacy_post_import (
    legacy_id BIGINT PRIMARY KEY,
    article_id BIGINT NOT NULL UNIQUE,
    source_fingerprint VARCHAR(64) NOT NULL,
    target_fingerprint VARCHAR(64) NOT NULL,
    source_zone VARCHAR(100) NOT NULL,
    target_status VARCHAR(20) NOT NULL,
    source_title VARCHAR(255) NOT NULL,
    source_content CLOB NOT NULL,
    request_mapping VARCHAR(255) NOT NULL,
    blog_menu_id BIGINT NOT NULL,
    source_datetime TIMESTAMP(9) NOT NULL,
    imported_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- No FK to modern_articles: keep provenance/tombstones after an intentional article deletion.
CREATE TABLE IF NOT EXISTS article_migration_run (
    fingerprint VARCHAR(64) PRIMARY KEY,
    actor VARCHAR(200) NOT NULL,
    source_zone VARCHAR(100) NOT NULL,
    target_status VARCHAR(20) NOT NULL,
    source_count INTEGER NOT NULL,
    imported_count INTEGER NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
