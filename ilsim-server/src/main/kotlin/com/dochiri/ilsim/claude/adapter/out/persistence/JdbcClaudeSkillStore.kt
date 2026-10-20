package com.dochiri.ilsim.claude.adapter.out.persistence

import com.dochiri.ilsim.claude.application.port.out.ClaudeSkillStore
import com.dochiri.ilsim.claude.domain.ClaudeSkill
import java.sql.Timestamp
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper

class JdbcClaudeSkillStore(private val jdbc: JdbcTemplate, private val json: JsonMapper) : ClaudeSkillStore {
    private val ready = AtomicBoolean(false)
    private val filesType = object : TypeReference<Map<String, String>>() {}

    private fun ensureTable() {
        if (ready.get()) return
        jdbc.execute(
            """
            CREATE TABLE IF NOT EXISTS claude_skills (
              name       VARCHAR(64)  NOT NULL PRIMARY KEY,
              files_json LONGTEXT     NOT NULL,
              updated_at TIMESTAMP(3) NOT NULL
            ) CHARACTER SET utf8mb4""",
        )
        ready.set(true)
    }

    override fun all(): List<ClaudeSkill> {
        ensureTable()
        return jdbc.query("SELECT name, files_json, updated_at FROM claude_skills ORDER BY name") { rs, _ ->
            ClaudeSkill(rs.getString(1), json.readValue(rs.getString(2), filesType).toSortedMap(), rs.getTimestamp(3).toInstant())
        }
    }

    override fun save(skill: ClaudeSkill) {
        ensureTable()
        jdbc.update(
            "INSERT INTO claude_skills (name, files_json, updated_at) VALUES (?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE files_json = VALUES(files_json), updated_at = VALUES(updated_at)",
            skill.name, json.writeValueAsString(skill.files), Timestamp.from(skill.updatedAt ?: Instant.now()),
        )
    }

    override fun delete(name: String): Boolean {
        ensureTable()
        return jdbc.update("DELETE FROM claude_skills WHERE name = ?", name) > 0
    }
}
