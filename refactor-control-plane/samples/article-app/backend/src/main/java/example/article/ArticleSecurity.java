package example.article;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class ArticleSecurity {
    @Bean UserDetailsService articleAccounts(@Value("${article.accounts-file:}") String file,ObjectMapper mapper) throws Exception {
        var users=new ArrayList<UserDetails>();
        if(!file.isBlank()) {
            Path path=Path.of(file).toAbsolutePath().normalize();
            if(Files.size(path)>65536)throw new IllegalArgumentException("Account file exceeds 64 KiB");
            var reader=mapper.copy();reader.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8).build());
            JsonNode accounts=reader.readTree(Files.readString(path,StandardCharsets.UTF_8));
            if(!accounts.isArray()||accounts.size()>100)throw new IllegalArgumentException("Account array required");
            var names=new HashSet<String>();
            for(var item:accounts) {
                String name=item.path("subject").asText(),hash=item.path("passwordHash").asText();
                if(!name.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,99}")||!names.add(name)||!hash.matches("\\{bcrypt}\\$2[aby]\\$[0-9]{2}\\$[./A-Za-z0-9]{53}")
                        || item.properties().stream().anyMatch(field->!Set.of("subject","passwordHash").contains(field.getKey())))throw new IllegalArgumentException("Unique subject and bcrypt hash required");
                users.add(User.withUsername(name).password(hash).roles("AUTHOR").build());
            }
        }
        return new InMemoryUserDetailsManager(users);
    }
    @Bean SecurityFilterChain articleFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth->auth.requestMatchers("/api/session","/actuator/health").permitAll()
                .requestMatchers("/api/manage/**").authenticated()
                .requestMatchers(new org.springframework.security.web.util.matcher.RegexRequestMatcher("^/(?!api/|actuator/)[^/]+/[^/]+/[^/]+$","GET")).permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.GET,"/api/articles/**","/api/public/**").permitAll().anyRequest().denyAll())
            .requestCache(cache->cache.disable())
            .exceptionHandling(errors->errors.authenticationEntryPoint((request,response,ex)->response.sendError(401)))
            .formLogin(login->login.loginProcessingUrl("/api/session/login").successHandler((request,response,auth)->response.setStatus(204))
                    .failureHandler((request,response,ex)->response.sendError(401)).permitAll())
            .logout(logout->logout.logoutUrl("/api/session/logout").logoutSuccessHandler((request,response,auth)->response.setStatus(204)));
        return http.build();
    }
}
