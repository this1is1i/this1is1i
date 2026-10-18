package io.refactorcontrolplane.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/demo")
public class DemoProjectController {

    private final io.refactorcontrolplane.project.ProjectSource service;

    public DemoProjectController(io.refactorcontrolplane.project.ProjectSource service) {
        this.service = service;
    }

    @GetMapping("/overview")
    public DemoProjectOverview overview() {
        return service.overview();
    }
}
