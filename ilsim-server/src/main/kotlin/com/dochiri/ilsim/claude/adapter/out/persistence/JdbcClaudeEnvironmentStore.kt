package com.dochiri.ilsim.claude.adapter.out.persistence

import com.dochiri.ilsim.claude.application.port.out.ClaudeEnvironmentStore
import com.dochiri.ilsim.claude.domain.ClaudeEnvironment
import java.sql.Timestamp
import java.util.concurrent.atomic.AtomicBoolean
import org.springframework.jdbc.core.JdbcTemplate

/** MySQL `claude_environment` 테이블(한 행). 테이블은 처음 쓸 때 만든다. */
class JdbcClaudeEnvironmentStore(private val jdbc: JdbcTemplate) : ClaudeEnvironmentStore {
    private val ready = AtomicBoolean(false)

    private fun ensureTable() {
        if (ready.get()) return
        jdbc.execute(
            """
            CREATE TABLE IF NOT EXISTS claude_environment (
              id            TINYINT      NOT NULL PRIMARY KEY,
              claude_md     LONGTEXT     NOT NULL,
              settings_json LONGTEXT     NOT NULL,
              updated_at    TIMESTAMP(3) NOT NULL
            ) CHARACTER SET utf8mb4""",
        )
        ready.set(true)
    }

    override fun load(): ClaudeEnvironment? {
        ensureTable()
        return jdbc.query("SELECT claude_md, settings_json, updated_at FROM claude_environment WHERE id = 1") { rs, _ ->
            ClaudeEnvironment(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant())
        }.firstOrNull()
    }

    override fun save(environment: ClaudeEnvironment) {
        ensureTable()
        jdbc.update(
            "INSERT INTO claude_environment (id, claude_md, settings_json, updated_at) VALUES (1, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE claude_md = VALUES(claude_md), settings_json = VALUES(settings_json), updated_at = VALUES(updated_at)",
            environment.claudeMd, environment.settingsJson, Timestamp.from(environment.updatedAt ?: java.time.Instant.now()),
        )
    }
}
