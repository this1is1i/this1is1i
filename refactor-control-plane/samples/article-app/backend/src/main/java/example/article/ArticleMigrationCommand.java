package example.article;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.ZoneId;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

public final class ArticleMigrationCommand {
    public static LegacyArticleMigration.Report execute(String[] args) throws Exception {
        if(args.length<2||args.length>3||!Set.of("preview","apply").contains(args[0])||args.length!=(args[0].equals("apply")?3:2))
            throw new IllegalArgumentException("Use --migration preview config.json or --migration apply config.json fingerprint");
        Path path=Path.of(args[1]).toAbsolutePath().normalize();
        var json=new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        json.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8).build());
        byte[] bytes;try(var input=Files.newInputStream(path)){bytes=input.readNBytes(65537);}
        if(bytes.length>65536)throw new IllegalArgumentException("Migration configuration exceeds 64 KiB");
        var config=json.readValue(new String(bytes,StandardCharsets.UTF_8),Configuration.class);
        var policy=new LegacyArticleMigration.Policy(ZoneId.of(config.sourceZone),ArticleStatus.valueOf(config.targetStatus),config.owners);
        Path accounts=Path.of(config.accountsFile);if(!accounts.isAbsolute())accounts=path.getParent().resolve(accounts);
        var users=new ArticleSecurity().articleAccounts(accounts.toString(),json);
        for(String subject:policy.owners().values())users.loadUserByUsername(subject);
        var tool=new LegacyArticleMigration(jdbc(config.source),jdbc(config.target),Set.copyOf(policy.owners().values()));
        return args[0].equals("preview")?tool.preview(policy):tool.apply(policy,args[2],config.operator);
    }
    private static JdbcTemplate jdbc(Database database) {
        if(database==null||database.url==null||!database.url.startsWith("jdbc:mysql:")||database.username==null||database.password==null)
            throw new IllegalArgumentException("Explicit MySQL source and target configuration required");
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(database.url,database.username,database.password));jdbc.setQueryTimeout(30);return jdbc;
    }
    private record Database(String url,String username,String password) {}
    private record Configuration(Database source,Database target,String accountsFile,String sourceZone,String targetStatus,Map<Long,String> owners,String operator) {}
}
