package com.dochiri.ilsim.task;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
class TaskController {

    record CreateTask(String projectId, String feature, String requirement) {}
    record Reject(String feedback) {}

    private final TaskService service;

    TaskController(TaskService service) {
        this.service = service;
    }

    @GetMapping("/projects")
    Object projects() {
        return service.projects();
    }

    @PostMapping("/tasks")
    Task create(@RequestBody CreateTask req) {
        return service.create(req.projectId(), req.feature(), req.requirement());
    }

    @GetMapping("/tasks")
    List<Task> list() {
        return service.list();
    }

    @GetMapping("/tasks/{id}")
    ResponseEntity<?> get(@PathVariable String id) {
        return service.find(id)
                .<ResponseEntity<?>>map(t -> ResponseEntity.ok(Map.of("task", t, "events", service.events(id))))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/tasks/{id}/approve")
    void approve(@PathVariable String id) {
        service.approve(id);
    }

    @PostMapping("/tasks/{id}/reject")
    void reject(@PathVariable String id, @RequestBody Reject req) {
        service.reject(id, req.feedback());
    }

    @PostMapping("/tasks/{id}/retry")
    void retry(@PathVariable String id) {
        service.retry(id);
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    ResponseEntity<Map<String, String>> badRequest(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<Map<String, String>> notFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }
}
