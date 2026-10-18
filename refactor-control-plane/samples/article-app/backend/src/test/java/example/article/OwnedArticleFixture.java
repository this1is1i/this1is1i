package example.article;

import org.springframework.jdbc.core.JdbcTemplate;

final class OwnedArticleFixture {
    static void seed(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO article_blogs(id,owner_subject,blog_url) SELECT 101,'tester','test-blog' WHERE NOT EXISTS(SELECT 1 FROM article_blogs WHERE id=101)");
        jdbc.update("INSERT INTO article_menus(id,blog_id,menu_name) SELECT 201,101,'articles' WHERE NOT EXISTS(SELECT 1 FROM article_menus WHERE id=201)");
    }
    static ArticleCommand command(String title,String content,ArticleStatus status) { return new ArticleCommand(title,content,status,201L,null); }
}
