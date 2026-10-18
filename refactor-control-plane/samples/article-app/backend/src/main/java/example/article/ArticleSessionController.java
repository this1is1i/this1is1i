package example.article;

import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
public class ArticleSessionController {
    @GetMapping("/api/session")
    ResponseEntity<?> session(Authentication actor,CsrfToken csrf) {
        boolean authenticated=actor!=null&&actor.isAuthenticated()&&!(actor instanceof AnonymousAuthenticationToken);
        return ResponseEntity.ok().header("Cache-Control","no-store").body(Map.of("authenticated",authenticated,"subject",authenticated?actor.getName():"",
                "csrfToken",csrf.getToken(),"csrfHeader",csrf.getHeaderName()));
    }
}
