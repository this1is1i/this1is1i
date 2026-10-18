-- Fresh independent target only. Existing unscoped schemas require an explicit migration.
CREATE TABLE IF NOT EXISTS article_blogs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    owner_subject VARCHAR(100) NOT NULL,
    blog_url VARCHAR(255) NOT NULL UNIQUE
);
CREATE TABLE IF NOT EXISTS article_menus (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    blog_id BIGINT NOT NULL REFERENCES article_blogs(id),
    menu_name VARCHAR(255) NOT NULL,
    UNIQUE(blog_id, menu_name)
);
CREATE TABLE IF NOT EXISTS modern_articles (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    -- H2 counts UTF-16 code units; the service enforces MySQL's 255-codepoint limit.
    title VARCHAR(510) NOT NULL,
    content TEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    menu_id BIGINT NOT NULL REFERENCES article_menus(id),
    slug VARCHAR(255) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE(menu_id, slug)
);
