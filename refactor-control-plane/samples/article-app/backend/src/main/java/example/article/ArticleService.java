package example.article;

import java.sql.*;
import java.time.*;
import java.util.*;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class ArticleService {
    private final JdbcTemplate jdbc;
    private final Clock clock = Clock.systemUTC();
    private static final String SELECT = """
        SELECT a.*, b.blog_url, m.menu_name FROM modern_articles a
        JOIN article_menus m ON m.id=a.menu_id JOIN article_blogs b ON b.id=m.blog_id
        """;
    public ArticleService(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @jakarta.annotation.PostConstruct
    void checkSchema() {
        try {
            jdbc.query(SELECT+" WHERE 1=0",(row,n)->article(row));
            jdbc.queryForList("SELECT menu_id,slug,version,deleted FROM modern_articles WHERE 1=0");
            String engine=jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>) connection->connection.getMetaData().getDatabaseProductName());
            if("MySQL".equals(engine) && jdbc.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
                    AND COLLATION_NAME='utf8mb4_0900_bin' AND ((TABLE_NAME='modern_articles' AND COLUMN_NAME='slug')
                    OR (TABLE_NAME='article_blogs' AND COLUMN_NAME='blog_url') OR (TABLE_NAME='article_menus' AND COLUMN_NAME='menu_name'))
                    """,Integer.class)!=3) throw new IllegalStateException("Exact NO PAD route collation required in the new target schema");
        }
        catch (org.springframework.dao.DataAccessException error) {
            throw new IllegalStateException("Scoped article schema required; preserve the old database and migrate explicitly into a new target",error);
        }
    }

    @Transactional
    public Article create(String subject, ArticleCommand command) {
        validate(command);
        if(command.menuId()==null || command.expectedVersion()!=null) throw bad("Creation requires menuId and no version");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM article_menus m JOIN article_blogs b ON b.id=m.blog_id WHERE m.id=? AND b.owner_subject=?",Integer.class,command.menuId(),subject)!=1)
            throw new ArticleNotFoundException(command.menuId());
        String slug="article-"+UUID.randomUUID();
        var keys=new GeneratedKeyHolder();
        jdbc.update(connection->{
            var statement=connection.prepareStatement("INSERT INTO modern_articles(title,content,status,menu_id,slug,updated_at) VALUES(?,?,?,?,?,?)",new String[]{"id"});
            statement.setString(1,command.title()); statement.setString(2,command.content()); statement.setString(3,command.status().name());
            statement.setLong(4,command.menuId()); statement.setString(5,slug); statement.setTimestamp(6,Timestamp.from(now()),utc());return statement;
        },keys);
        if(keys.getKey()==null)throw new IllegalStateException("Database did not return an article ID");
        return find(subject,keys.getKey().longValue());
    }
    public List<Article> findAll(String subject) {
        return jdbc.query(SELECT+" WHERE b.owner_subject=? AND a.deleted=FALSE ORDER BY a.id",(row,n)->article(row),subject);
    }
    public List<Menu> menus(String subject) {
        return jdbc.query("SELECT m.id,b.blog_url,m.menu_name FROM article_menus m JOIN article_blogs b ON b.id=m.blog_id WHERE b.owner_subject=? ORDER BY m.id",
            (row,n)->new Menu(row.getLong(1),row.getString(2),row.getString(3)),subject);
    }
    public Article find(String subject,long id) {
        return jdbc.query(SELECT+" WHERE a.id=? AND b.owner_subject=? AND a.deleted=FALSE",(row,n)->article(row),id,subject)
            .stream().findFirst().orElseThrow(()->new ArticleNotFoundException(id));
    }
    @Transactional
    public Article update(String subject,long id,ArticleCommand command) {
        var current=find(subject,id);validate(command);
        if(command.menuId()!=null && command.menuId()!=current.menuId())throw conflict("Menu ownership is immutable");
        version(command.expectedVersion());
        int changed=jdbc.update(connection->{
            var statement=connection.prepareStatement("""
                UPDATE modern_articles SET title=?,content=?,status=?,updated_at=?,version=version+1
                WHERE id=? AND version=? AND deleted=FALSE AND menu_id IN
                (SELECT m.id FROM article_menus m JOIN article_blogs b ON b.id=m.blog_id WHERE b.owner_subject=?)
                """);
            statement.setString(1,command.title());statement.setString(2,command.content());statement.setString(3,command.status().name());
            statement.setTimestamp(4,Timestamp.from(now()),utc());statement.setLong(5,id);statement.setLong(6,command.expectedVersion());statement.setString(7,subject);return statement;
        });
        if(changed!=1)throw conflict("Article changed; refresh before editing");
        return find(subject,id);
    }
    @Transactional
    public void delete(String subject,long id,Long expectedVersion) {
        find(subject,id);version(expectedVersion);
        int changed=jdbc.update("""
            UPDATE modern_articles SET deleted=TRUE,version=version+1 WHERE id=? AND version=? AND deleted=FALSE
            AND menu_id IN (SELECT m.id FROM article_menus m JOIN article_blogs b ON b.id=m.blog_id WHERE b.owner_subject=?)
            """,id,expectedVersion,subject);
        if(changed!=1)throw conflict("Article changed; refresh before deleting");
    }
    public List<Publication> published() {
        return jdbc.query(SELECT+" WHERE a.status='PUBLISHED' AND a.deleted=FALSE ORDER BY a.id",(row,n)->publication(article(row)));
    }
    public Publication published(long id) {
        return jdbc.query(SELECT+" WHERE a.id=? AND a.status='PUBLISHED' AND a.deleted=FALSE",(row,n)->publication(article(row)),id)
            .stream().findFirst().orElseThrow(()->new ArticleNotFoundException(id));
    }
    public Publication published(String blog,String menu,String slug) {
        return jdbc.query(SELECT+" WHERE b.blog_url=? AND m.menu_name=? AND a.slug=? AND a.status='PUBLISHED' AND a.deleted=FALSE",(row,n)->publication(article(row)),blog,menu,slug)
            .stream().findFirst().orElseThrow(()->new ArticleNotFoundException(0));
    }
    private static Publication publication(Article article) {
        var safe=Safelist.relaxed().preserveRelativeLinks(true).addEnforcedAttribute("a","rel","nofollow noopener noreferrer");
        String html=Jsoup.clean(article.content(),"https://article.invalid/",safe);
        return new Publication(article.id(),article.title(),html,article.publicPath(),article.updatedAt());
    }
    private static Article article(ResultSet row) throws SQLException {
        String path=UriComponentsBuilder.fromPath("").pathSegment(row.getString("blog_url"),row.getString("menu_name"),row.getString("slug")).build().encode().toUriString();
        return new Article(row.getLong("id"),row.getString("title"),row.getString("content"),ArticleStatus.valueOf(row.getString("status")),
            row.getTimestamp("updated_at",utc()).toInstant(),row.getLong("menu_id"),row.getLong("version"),path);
    }
    private static void validate(ArticleCommand command) {
        if(command.title()==null || command.title().isBlank() || command.title().codePointCount(0,command.title().length())>255 || command.content()==null || command.content().isBlank() || command.status()==null)
            throw bad("Title, body and status are required");
        if(command.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>1048576)throw bad("Body exceeds 1 MiB");
    }
    private static void version(Long version) { if(version==null || version<1)throw bad("expectedVersion is required"); }
    private Instant now() { return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS); }
    private static Calendar utc() { return Calendar.getInstance(TimeZone.getTimeZone("UTC")); }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT,message); }
    public record Menu(long id,String blogUrl,String menuName) {}
    public record Publication(long id,String title,String html,String publicPath,Instant updatedAt) {}
}
