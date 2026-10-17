package com.dochiri.ilsim.secret.adapter.out.persistence

import com.dochiri.ilsim.secret.application.port.out.SecretRepository
import com.dochiri.ilsim.secret.application.port.out.StoredSecret
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import org.springframework.jdbc.core.JdbcTemplate

/** MySQL `secrets` 테이블. 테이블은 처음 쓸 때 만든다(DB 가 아직 없을 수 있어 기동 시점에는 건드리지 않는다). */
class JpaSecretRepository(
    private val jdbc: JdbcTemplate,
    private val secrets: SecretJpaRepository,
) : SecretRepository {

    private val ready = AtomicBoolean(false)

    private fun ensureTable() {
        if (ready.get()) return
        jdbc.execute(
            """
            CREATE TABLE IF NOT EXISTS secrets (
              name        VARCHAR(100) NOT NULL PRIMARY KEY,
              cipher_text TEXT         NOT NULL,
              updated_at  TIMESTAMP(3) NOT NULL
            ) CHARACTER SET utf8mb4""",
        )
        ready.set(true)
    }

    override fun find(name: String): StoredSecret? {
        ensureTable()
        return secrets.findById(name).map { StoredSecret(it.cipherText, it.updatedAt) }.orElse(null)
    }

    override fun save(name: String, cipherText: String, updatedAt: Instant) {
        ensureTable()
        val entity = secrets.findById(name).orElse(null)
        if (entity == null) {
            secrets.save(SecretEntity.of(name, cipherText, updatedAt))
        } else {
            entity.update(cipherText, updatedAt)
            secrets.save(entity)
        }
    }

    override fun delete(name: String) {
        ensureTable()
        secrets.deleteById(name)
    }
}
