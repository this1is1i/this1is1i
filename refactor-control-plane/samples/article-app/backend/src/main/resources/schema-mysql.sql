-- New independent sample schema only. No ALTER/DROP/UPDATE of legacy blog tables.
CREATE TABLE IF NOT EXISTS article_blogs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    owner_subject VARCHAR(100) NOT NULL,
    blog_url VARCHAR(255) COLLATE utf8mb4_0900_bin NOT NULL UNIQUE
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
CREATE TABLE IF NOT EXISTS article_menus (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    blog_id BIGINT NOT NULL,
    menu_name VARCHAR(255) COLLATE utf8mb4_0900_bin NOT NULL,
    FOREIGN KEY(blog_id) REFERENCES article_blogs(id),
    UNIQUE(blog_id, menu_name)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
CREATE TABLE IF NOT EXISTS modern_articles (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    title VARCHAR(255) NOT NULL,
    content LONGTEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    menu_id BIGINT NOT NULL,
    slug VARCHAR(255) COLLATE utf8mb4_0900_bin NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at DATETIME(6) NOT NULL,
    FOREIGN KEY(menu_id) REFERENCES article_menus(id),
    UNIQUE(menu_id, slug)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

CREATE TABLE IF NOT EXISTS article_migration_lock(id INT PRIMARY KEY, source_identity VARCHAR(64)) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
INSERT INTO article_migration_lock(id) SELECT 1 WHERE NOT EXISTS(SELECT 1 FROM article_migration_lock WHERE id=1);
CREATE TABLE IF NOT EXISTS legacy_post_import(
    legacy_id BIGINT PRIMARY KEY, article_id BIGINT NOT NULL UNIQUE,
    source_fingerprint VARCHAR(64) NOT NULL, target_fingerprint VARCHAR(64) NOT NULL,
    source_json LONGTEXT NOT NULL, policy_json LONGTEXT NOT NULL
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
CREATE TABLE IF NOT EXISTS article_migration_run(
    fingerprint VARCHAR(64) PRIMARY KEY, actor VARCHAR(100) NOT NULL,
    source_count INT NOT NULL, imported_count INT NOT NULL
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
