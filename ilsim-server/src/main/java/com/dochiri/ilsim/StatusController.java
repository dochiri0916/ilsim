package com.dochiri.ilsim;

import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class StatusController {

    @GetMapping("/api/status")
    Map<String, String> status() {
        return Map.of("name", "ilsim-server", "version", "0.0.1", "time", Instant.now().toString());
    }
}
