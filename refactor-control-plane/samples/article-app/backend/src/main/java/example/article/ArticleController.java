package example.article;

import jakarta.validation.Valid;
import java.net.URI;
import java.security.Principal;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class ArticleController {
    private final ArticleService service;
    public ArticleController(ArticleService service) { this.service=service; }
    @GetMapping("/api/articles") public List<ArticleService.Publication> published() { return service.published(); }
    @GetMapping("/api/articles/{id}") public ArticleService.Publication published(@PathVariable long id) { return service.published(id); }
    @GetMapping({"/{blog}/{menu}/{slug}","/api/public/{blog}/{menu}/{slug}"}) public ArticleService.Publication legacy(@PathVariable String blog,@PathVariable String menu,@PathVariable String slug) { return service.published(blog,menu,slug); }
    @GetMapping("/api/manage/menus") public List<ArticleService.Menu> menus(Principal actor) { return service.menus(actor.getName()); }
    @GetMapping("/api/manage/articles") public List<Article> findAll(Principal actor) { return service.findAll(actor.getName()); }
    @GetMapping("/api/manage/articles/{id}") public Article find(Principal actor,@PathVariable long id) { return service.find(actor.getName(),id); }
    @PostMapping("/api/manage/articles") public ResponseEntity<Article> create(Principal actor,@Valid @RequestBody ArticleCommand command) {
        var created=service.create(actor.getName(),command);
        return ResponseEntity.created(URI.create("/api/manage/articles/"+created.id())).header("Cache-Control","no-store").body(created);
    }
    @PutMapping("/api/manage/articles/{id}") public Article update(Principal actor,@PathVariable long id,@Valid @RequestBody ArticleCommand command) { return service.update(actor.getName(),id,command); }
    @DeleteMapping("/api/manage/articles/{id}") public ResponseEntity<Void> delete(Principal actor,@PathVariable long id,@RequestParam Long expectedVersion) {
        service.delete(actor.getName(),id,expectedVersion);return ResponseEntity.noContent().build();
    }
}
