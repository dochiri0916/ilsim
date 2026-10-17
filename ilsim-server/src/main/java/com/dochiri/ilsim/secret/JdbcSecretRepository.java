package com.dochiri.ilsim.secret;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** MySQL `secrets` 테이블. 테이블은 처음 쓸 때 만든다(DB 가 아직 없을 수 있어 기동 시점에는 건드리지 않는다). */
@Repository
class JdbcSecretRepository implements SecretRepository {

    private final JdbcTemplate jdbc;
    private final AtomicBoolean ready = new AtomicBoolean(false);

    JdbcSecretRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private void ensureTable() {
        if (ready.get()) return;
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS secrets (
                  name        VARCHAR(100) NOT NULL PRIMARY KEY,
                  cipher_text TEXT         NOT NULL,
                  updated_at  TIMESTAMP(3) NOT NULL
                ) CHARACTER SET utf8mb4""");
        ready.set(true);
    }

    @Override
    public Optional<Stored> find(String name) {
        ensureTable();
        return jdbc.query("SELECT cipher_text, updated_at FROM secrets WHERE name = ?",
                (rs, i) -> new Stored(rs.getString(1), rs.getTimestamp(2).toInstant()), name).stream().findFirst();
    }

    @Override
    public void save(String name, String cipherText, Instant updatedAt) {
        ensureTable();
        jdbc.update("""
                INSERT INTO secrets (name, cipher_text, updated_at) VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE cipher_text = VALUES(cipher_text), updated_at = VALUES(updated_at)""",
                name, cipherText, Timestamp.from(updatedAt));
    }

    @Override
    public void delete(String name) {
        ensureTable();
        jdbc.update("DELETE FROM secrets WHERE name = ?", name);
    }
}
