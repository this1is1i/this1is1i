package example.article;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ArticleApplication {
    public static void main(String[] args) throws Exception {
        if(args.length>0&&args[0].equals("--migration")) {
            try {System.out.println(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(ArticleMigrationCommand.execute(java.util.Arrays.copyOfRange(args,1,args.length))));}
            catch(Exception error){throw new IllegalStateException("Migration refused or result unconfirmed; inspect source, preview and target journal before retrying");}
            return;
        }
        SpringApplication.run(ArticleApplication.class,args);
    }
}
