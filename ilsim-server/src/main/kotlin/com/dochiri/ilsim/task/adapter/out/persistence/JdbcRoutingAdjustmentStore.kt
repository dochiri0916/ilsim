package com.dochiri.ilsim.task.adapter.out.persistence

import com.dochiri.ilsim.task.application.port.out.RoutingAdjustmentStore
import com.dochiri.ilsim.task.domain.RoutingAdjustment
import com.dochiri.ilsim.task.domain.RoutingAdjustments
import java.sql.Timestamp
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

class JdbcRoutingAdjustmentStore(private val jdbc: JdbcTemplate) : RoutingAdjustmentStore {
    private val ready = AtomicBoolean(false)
    private val json: JsonMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build()

    data class Row(val items: List<RoutingAdjustment> = emptyList(), val note: String? = null)

    private fun ensureTable() {
        if (ready.get()) return
        jdbc.execute(
            """
            CREATE TABLE IF NOT EXISTS routing_adjustments (
              id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
              body       LONGTEXT     NOT NULL,
              created_at TIMESTAMP(3) NOT NULL
            ) CHARACTER SET utf8mb4""",
        )
        ready.set(true)
    }

    override fun loadAll(): List<RoutingAdjustments> {
        ensureTable()
        return jdbc.query("SELECT body, created_at FROM routing_adjustments ORDER BY id") { rs, _ ->
            val row = json.readValue(rs.getString(1), Row::class.java)
            RoutingAdjustments(row.items, row.note, rs.getTimestamp(2).toInstant())
        }
    }

    override fun save(adjustments: RoutingAdjustments) {
        ensureTable()
        jdbc.update("INSERT INTO routing_adjustments (body, created_at) VALUES (?, ?)",
            json.writeValueAsString(Row(adjustments.items, adjustments.note)), Timestamp.from(adjustments.createdAt ?: Instant.now()))
    }
}
