package com.dochiri.ilsim.secret;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/** 비밀 값 등록·조회. 저장은 암호화해서 하고, 조회는 짧게 메모리에 캐시해 DB 를 매번 읽지 않는다. */
@Service
public class SecretService {

    public static final String JEV_API_KEY = "jev-api-key";
    private static final long CACHE_MILLIS = 30_000;

    public static class Unavailable extends RuntimeException {
        public Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** last4 는 확인용으로만 쓴다. 값 자체는 어떤 응답에도 담지 않는다. */
    public record Status(boolean configured, String last4, Instant updatedAt) {}

    private record Cached(Optional<String> value, Optional<Instant> updatedAt, long loadedAt) {}

    private final SecretRepository repository;
    private final SecretCipher cipher;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public SecretService(SecretRepository repository, SecretCipher cipher) {
        this.repository = repository;
        this.cipher = cipher;
    }

    public void put(String name, String plain) {
        if (plain == null || plain.isBlank() || plain.length() > 512 || plain.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("키 형식이 올바르지 않습니다");
        }
        try {
            repository.save(name, cipher.encrypt(name, plain), Instant.now());
        } catch (RuntimeException e) {
            throw unavailable(e);
        }
        cache.remove(name);
    }

    public void delete(String name) {
        try {
            repository.delete(name);
        } catch (RuntimeException e) {
            throw unavailable(e);
        }
        cache.remove(name);
    }

    public Optional<String> get(String name) {
        return load(name).value();
    }

    public Status status(String name) {
        Cached c = load(name);
        return new Status(c.value().isPresent(), c.value().map(v -> v.substring(Math.max(0, v.length() - 4))).orElse(null), c.updatedAt().orElse(null));
    }

    private Cached load(String name) {
        Cached cached = cache.get(name);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.loadedAt() < CACHE_MILLIS) return cached;
        Optional<SecretRepository.Stored> stored;
        try {
            stored = repository.find(name);
        } catch (RuntimeException e) {
            throw unavailable(e);
        }
        // 복호화 실패는 DB 문제가 아니라 마스터 키 문제라 그대로 올린다.
        Cached fresh = new Cached(stored.map(s -> cipher.decrypt(name, s.cipherText())), stored.map(SecretRepository.Stored::updatedAt), now);
        cache.put(name, fresh);
        return fresh;
    }

    private static Unavailable unavailable(RuntimeException e) {
        return new Unavailable("키 저장소(MySQL)에 연결할 수 없습니다", e);
    }
}
