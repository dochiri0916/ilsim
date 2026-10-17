package com.dochiri.ilsim.secret;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 웹에서 JEV API 키를 등록·삭제한다. 키 값은 응답에 담지 않는다(마지막 4자리만). 인증은 TokenFilter 가 /api/**, /runner/** 에 건다.
 * Runner 는 작업을 받을 때 `/runner/api/secrets/jev` 로 키를 받아 메모리에만 두고 쓴다.
 */
@RestController
class SettingsController {

    record KeyRequest(String key) {}

    private final SecretService secrets;

    SettingsController(SecretService secrets) {
        this.secrets = secrets;
    }

    @GetMapping("/api/settings")
    Map<String, Object> settings() {
        SecretService.Status s = secrets.status(SecretService.JEV_API_KEY);
        Map<String, Object> jev = new LinkedHashMap<>();
        jev.put("configured", s.configured());
        jev.put("last4", s.last4());
        jev.put("updatedAt", s.updatedAt());
        return Map.of("jev", jev);
    }

    @PutMapping("/api/settings/jev-key")
    Map<String, Object> put(@RequestBody KeyRequest request) {
        secrets.put(SecretService.JEV_API_KEY, request.key() == null ? null : request.key().trim());
        return settings();
    }

    @DeleteMapping("/api/settings/jev-key")
    Map<String, Object> delete() {
        secrets.delete(SecretService.JEV_API_KEY);
        return settings();
    }

    @GetMapping("/runner/api/secrets/jev")
    ResponseEntity<Map<String, String>> runnerKey() {
        return secrets.get(SecretService.JEV_API_KEY)
                .map(k -> ResponseEntity.ok(Map.of("key", k)))
                .orElse(ResponseEntity.noContent().build());
    }

    @ExceptionHandler(SecretService.Unavailable.class)
    ResponseEntity<Map<String, String>> unavailable(SecretService.Unavailable e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
