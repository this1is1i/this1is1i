package example.article;

import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Local operator tool: source SELECTs only, independent target transaction, no HTTP registration. */
public class LegacyArticleMigration {
    private final JdbcTemplate source,target;
    private final ArticleService articles;
    private final Set<String> subjects;
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final TransactionTemplate read,write;
    private final String sourceIdentity,targetIdentity;
    public LegacyArticleMigration(JdbcTemplate source,JdbcTemplate target,Set<String> subjects) {
        this.source=source;this.target=target;this.subjects=Set.copyOf(subjects);this.articles=new ArticleService(target);articles.checkSchema();
        sourceIdentity=identity(source);targetIdentity=identity(target);
        if(sourceIdentity.equals(targetIdentity))throw new IllegalArgumentException("Separate source and target schemas required");
        read=transaction(source,true);write=transaction(target,false);
    }
    private static TransactionTemplate transaction(JdbcTemplate jdbc,boolean readOnly) {
        var tx=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        tx.setReadOnly(readOnly);tx.setIsolationLevel(readOnly?TransactionDefinition.ISOLATION_REPEATABLE_READ:TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(30);return tx;
    }
    public Report preview(Policy policy) { return read.execute(status->inspect(policy).report(0)); }
    public Report apply(Policy policy,String fingerprint,String actor) {
        if(fingerprint==null || !fingerprint.matches("[0-9a-f]{64}") || actor==null || !actor.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,99}"))
            throw new IllegalArgumentException("Preview fingerprint and operator subject required");
        return write.execute(status->{
            target.queryForObject("SELECT id FROM article_migration_lock WHERE id=1 FOR UPDATE",Integer.class);
            return read.execute(snapshot->{
                var inspected=inspect(policy);
                if(!inspected.fingerprint.equals(fingerprint) || !inspected.issues.isEmpty())throw new IllegalStateException("Source, policy or target conflict; preview again");
                target.update("UPDATE article_migration_lock SET source_identity=? WHERE id=1",sourceIdentity);
                int imported=0;
                for(var row:inspected.pending) {
                    String owner=policy.owners.get(row.blogId);
                    long blog=blog(row,owner),menu=menu(row,blog);
                    var keys=new GeneratedKeyHolder();
                    Instant time=row.dateTime.toInstant(policy.sourceZone.getRules().getValidOffsets(row.dateTime).getFirst()).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
                    target.update(connection->{
                        var statement=connection.prepareStatement("INSERT INTO modern_articles(title,content,status,menu_id,slug,updated_at) VALUES(?,?,?,?,?,?)",new String[]{"id"});
                        statement.setString(1,row.title);statement.setString(2,row.content);statement.setString(3,policy.targetStatus.name());statement.setLong(4,menu);
                        statement.setString(5,row.slug);statement.setTimestamp(6,Timestamp.from(time),Calendar.getInstance(TimeZone.getTimeZone("UTC")));return statement;
                    },keys);
                    if(keys.getKey()==null)throw new IllegalStateException("Missing generated target ID");
                    long id=keys.getKey().longValue();
                    target.update("INSERT INTO legacy_post_import(legacy_id,article_id,source_fingerprint,target_fingerprint,source_json,policy_json) VALUES(?,?,?,?,?,?)",
                        row.id,id,rowHash(row,policy),hash(articles.find(owner,id)),encode(row),encode(policy));
                    imported++;
                }
                if(imported>0)target.update("INSERT INTO article_migration_run(fingerprint,actor,source_count,imported_count) VALUES(?,?,?,?)",fingerprint,actor,inspected.rows.size(),imported);
                return new Report(fingerprint,inspected.rows.size(),0,inspected.alreadyImported,imported,List.of());
            });
        });
    }
    private Inspection inspect(Policy policy) {
        Objects.requireNonNull(policy);
        var size=source.queryForMap("""
            SELECT COUNT(*) AS row_count,COALESCE(SUM(COALESCE(CHAR_LENGTH(p.PostTitle),0)+COALESCE(CHAR_LENGTH(p.PostContent),0)
            +COALESCE(CHAR_LENGTH(p.RequestMapping),0)+COALESCE(CHAR_LENGTH(b.BlogUrl),0)+COALESCE(CHAR_LENGTH(m.MenuName),0)),0) AS text_size
            FROM posts p LEFT JOIN blogsmenus bm ON bm.Id=p.BlogMenuId LEFT JOIN blogs b ON b.Id=bm.BlogId LEFT JOIN menus m ON m.Id=bm.MenuId
            """);
        if(((Number)size.get("row_count")).longValue()>10000 || ((Number)size.get("text_size")).longValue()>16*1024*1024)
            throw new IllegalStateException("Source exceeds 10000 rows or 16 MiB preflight budget");
        var rows=source.query("""
            SELECT p.Id,p.PostTitle,p.PostContent,p.RequestMapping,p.BlogMenuId,p.DateTime,
              b.Id AS blog_id,b.UserId,b.BlogUrl,m.Id AS menu_id,m.MenuName
            FROM posts p LEFT JOIN blogsmenus bm ON bm.Id=p.BlogMenuId LEFT JOIN blogs b ON b.Id=bm.BlogId
            LEFT JOIN menus m ON m.Id=bm.MenuId ORDER BY p.Id LIMIT 10001
            """,(r,n)->new Row(r.getObject(1,Long.class),r.getString(2),r.getString(3),r.getString(4),r.getObject(5,Long.class),
                r.getObject(6,LocalDateTime.class),r.getObject(7,Long.class),r.getObject(8,Long.class),r.getString(9),r.getObject(10,Long.class),r.getString(11)));
        if(rows.size()>10000)throw new IllegalStateException("Source changed beyond row budget");
        var issues=new ArrayList<Issue>();var pending=new ArrayList<Row>();var seen=new HashSet<Long>();var routes=new HashSet<List<String>>();
        var blogIds=new HashSet<Long>();long bytes=0;int existing=0;
        String binding=target.queryForObject("SELECT source_identity FROM article_migration_lock WHERE id=1",String.class);
        if(binding!=null&&!binding.equals(sourceIdentity))issues.add(new Issue(null,"Target is bound to another source schema"));
        for(var row:rows) {
            bytes+=encode(row).getBytes(StandardCharsets.UTF_8).length;
            if(bytes>16*1024*1024)throw new IllegalStateException("Source exceeds UTF-8 byte budget");
            if(!seen.add(row.id))issues.add(new Issue(row.id,"Duplicate source ID"));
            if(row.blogId!=null)blogIds.add(row.blogId);
            if(row.id==null || row.id<1 || row.title==null || row.title.isBlank() || row.title.codePointCount(0,row.title.length())>255
                || row.content==null || row.content.isBlank() || row.content.getBytes(StandardCharsets.UTF_8).length>1048576
                || row.blogId==null || row.userId==null || row.menuId==null || row.blogMenuId==null || row.dateTime==null
                || !segment(row.blogUrl) || !segment(row.menuName) || !segment(row.slug) || Set.of("api","actuator").contains(row.blogUrl)) {
                issues.add(new Issue(row.id,"Invalid field, association or route; no normalization is permitted"));continue;
            }
            String owner=policy.owners.get(row.blogId);
            if(owner==null || !subjects.contains(owner)) { issues.add(new Issue(row.id,"Explicit known account required for source blog"));continue; }
            if(policy.sourceZone.getRules().getValidOffsets(row.dateTime).size()!=1) {issues.add(new Issue(row.id,"Ambiguous or missing local time; review an explicit fixed offset"));continue;}
            if(!routes.add(List.of(row.blogUrl,row.menuName,row.slug)))issues.add(new Issue(row.id,"Duplicate source public path"));
            var blogs=target.queryForList("SELECT owner_subject FROM article_blogs WHERE blog_url=?",String.class,row.blogUrl);
            if(!blogs.isEmpty()&&!owner.equals(blogs.getFirst()))issues.add(new Issue(row.id,"Existing blog belongs to another account"));
            var mappings=target.queryForList("SELECT article_id,source_fingerprint,target_fingerprint FROM legacy_post_import WHERE legacy_id=?",row.id);
            if(mappings.isEmpty()) {
                int conflicts=target.queryForObject("SELECT COUNT(*) FROM modern_articles a JOIN article_menus m ON m.id=a.menu_id JOIN article_blogs b ON b.id=m.blog_id WHERE b.blog_url=? AND m.menu_name=? AND a.slug=?",Integer.class,row.blogUrl,row.menuName,row.slug);
                if(conflicts>0)issues.add(new Issue(row.id,"Public path already reserved, including deleted target"));else pending.add(row);
            } else {
                var mapping=mappings.getFirst();
                if(!rowHash(row,policy).equals(mapping.get("source_fingerprint"))) {issues.add(new Issue(row.id,"Previously imported source or policy changed"));continue;}
                try {
                    if(!hash(articles.find(owner,((Number)mapping.get("article_id")).longValue())).equals(mapping.get("target_fingerprint")))
                        issues.add(new Issue(row.id,"Target edited; preserving current state"));
                    else existing++;
                } catch(ArticleNotFoundException error) {issues.add(new Issue(row.id,"Target deleted; preserving tombstone and mapping"));}
            }
        }
        if(!policy.owners.keySet().equals(blogIds))issues.add(new Issue(null,"Owner mapping must exactly cover blogs referenced by posts"));
        for(long prior:target.queryForList("SELECT legacy_id FROM legacy_post_import",Long.class))if(!seen.contains(prior))issues.add(new Issue(prior,"Previously imported source disappeared"));
        return new Inspection(rows,hash(List.of("article-import/v2",sourceIdentity,targetIdentity,policy,rows)),pending,existing,issues);
    }
    private long blog(Row row,String owner) {
        var ids=target.queryForList("SELECT id FROM article_blogs WHERE blog_url=?",Long.class,row.blogUrl);
        if(!ids.isEmpty())return ids.getFirst();
        return insert("INSERT INTO article_blogs(owner_subject,blog_url) VALUES(?,?)",owner,row.blogUrl);
    }
    private long menu(Row row,long blog) {
        var ids=target.queryForList("SELECT id FROM article_menus WHERE blog_id=? AND menu_name=?",Long.class,blog,row.menuName);
        return ids.isEmpty()?insert("INSERT INTO article_menus(blog_id,menu_name) VALUES(?,?)",blog,row.menuName):ids.getFirst();
    }
    private long insert(String sql,Object... values) {
        var keys=new GeneratedKeyHolder();target.update(connection->{var s=connection.prepareStatement(sql,new String[]{"id"});for(int i=0;i<values.length;i++)s.setObject(i+1,values[i]);return s;},keys);
        if(keys.getKey()==null)throw new IllegalStateException("Missing association ID");return keys.getKey().longValue();
    }
    private String identity(JdbcTemplate jdbc) {
        return jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>) connection->{
            String server=connection.getMetaData().getURL().split(";",2)[0],schema=Objects.toString(connection.getCatalog(),"");
            if("MySQL".equals(connection.getMetaData().getDatabaseProductName()))try(var statement=connection.createStatement()) {
                statement.setQueryTimeout(30);try(var row=statement.executeQuery("SELECT @@server_uuid,DATABASE(),@@lower_case_table_names")) {
                    row.next();server=row.getString(1);schema=row.getString(2);
                    if(schema==null)throw new IllegalArgumentException("Explicit source and target database required");
                    if(row.getInt(3)!=0)schema=schema.toLowerCase(Locale.ROOT);
                }
            }
            return hash(List.of(server,schema));
        });
    }
    private static boolean segment(String value) {
        return value!=null&&!value.isBlank()&&value.codePointCount(0,value.length())<=255&&!Set.of(".","..").contains(value)
            &&value.chars().noneMatch(c->c<32||c==127||"/\\?#%;".indexOf(c)>=0);
    }
    private String rowHash(Row row,Policy policy) {return hash(List.of(row,policy.sourceZone.getId(),policy.targetStatus,policy.owners.get(row.blogId)));}
    private String encode(Object value) {try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("Cannot encode migration record");}}
    private String hash(Object value) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(value).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("Cannot fingerprint migration");}}
    public record Policy(ZoneId sourceZone,ArticleStatus targetStatus,Map<Long,String> owners) {
        public Policy {
            if(sourceZone==null||targetStatus==null||owners==null||owners.size()>10000)throw new IllegalArgumentException("Explicit zone, status and owners required");
            owners=Collections.unmodifiableMap(new TreeMap<>(owners));
            if(owners.entrySet().stream().anyMatch(e->e.getKey()<1||e.getValue()==null||!e.getValue().matches("[A-Za-z0-9][A-Za-z0-9_-]{0,99}")))throw new IllegalArgumentException("Invalid owner mapping");
        }
    }
    public record Issue(Long legacyId,String reason) {}
    public record Report(String fingerprint,int sourceCount,int pending,int alreadyImported,int imported,List<Issue> issues) {public Report{issues=List.copyOf(issues);}}
    private record Row(Long id,String title,String content,String slug,Long blogMenuId,LocalDateTime dateTime,Long blogId,Long userId,String blogUrl,Long menuId,String menuName) {}
    private record Inspection(List<Row> rows,String fingerprint,List<Row> pending,int alreadyImported,List<Issue> issues) {
        Report report(int imported){return new Report(fingerprint,rows.size(),pending.size(),alreadyImported,imported,issues);}
    }
}
