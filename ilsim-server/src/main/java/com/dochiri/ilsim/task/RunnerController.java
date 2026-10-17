package com.dochiri.ilsim.task;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/runner/api")
class RunnerController {

    record Poll(String runnerId, List<TaskService.Project> projects) {}
    record Event(String stage, String type, String data) {}

    private final TaskService service;

    RunnerController(TaskService service) {
        this.service = service;
    }

    @PostMapping("/poll")
    ResponseEntity<?> poll(@RequestBody Poll req) {
        return service.poll(req.runnerId(), req.projects())
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    @PostMapping("/tasks/{id}/events")
    void event(@PathVariable String id, @RequestBody Event e) {
        service.addEvent(id, e.stage(), e.type(), e.data());
    }

    @PostMapping("/tasks/{id}/result")
    void result(@PathVariable String id, @RequestBody TaskService.StageResult result) {
        service.report(id, result);
    }
}
