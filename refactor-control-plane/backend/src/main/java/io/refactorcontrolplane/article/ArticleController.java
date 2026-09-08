package io.refactorcontrolplane.article;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/articles")
public class ArticleController {

    private final ArticleService service;

    public ArticleController(ArticleService service) {
        this.service = service;
    }

    @GetMapping
    public List<Article> findAll() { return service.findAll(); }

    @GetMapping("/{id}")
    public Article find(@PathVariable long id) { return service.find(id); }

    @PostMapping
    public ResponseEntity<Article> create(@Valid @RequestBody ArticleCommand command) {
        Article created = service.create(command);
        return ResponseEntity.created(URI.create("/api/articles/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public Article update(@PathVariable long id, @Valid @RequestBody ArticleCommand command) {
        return service.update(id, command);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
