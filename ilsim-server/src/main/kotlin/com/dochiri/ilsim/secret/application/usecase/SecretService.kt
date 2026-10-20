package com.dochiri.ilsim.secret.application.usecase

import com.dochiri.ilsim.secret.api.SecretReader
import com.dochiri.ilsim.secret.api.SecretWriter
import com.dochiri.ilsim.secret.application.port.`in`.ManageSecretUseCase
import com.dochiri.ilsim.secret.application.port.out.SecretEncryptor
import com.dochiri.ilsim.secret.application.port.out.SecretRepository
import com.dochiri.ilsim.secret.application.port.out.StoredSecret
import com.dochiri.ilsim.secret.domain.SecretStatus
import com.dochiri.ilsim.secret.domain.SecretValue
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class SecretService(
    private val repository: SecretRepository,
    private val encryptor: SecretEncryptor,
    private val clock: Clock,
) : ManageSecretUseCase, SecretReader, SecretWriter {

    class Unavailable(message: String, cause: Throwable) : RuntimeException(message, cause)

    private class Cached(val value: String?, val updatedAt: Instant?, val loadedAt: Instant)

    private val cache = ConcurrentHashMap<String, Cached>()

    override fun put(name: String, plain: String?) {
        val value = SecretValue.validate(plain)
        guarded { repository.save(name, encryptor.encrypt(name, value), clock.instant()) }
        cache.remove(name)
    }

    override fun delete(name: String) {
        guarded { repository.delete(name) }
        cache.remove(name)
    }

    override fun get(name: String): String? = load(name).value

    override fun status(name: String): SecretStatus {
        val c = load(name)
        return SecretStatus(c.value != null, c.value?.let(SecretValue::last4), c.updatedAt)
    }

    private fun load(name: String): Cached {
        val now = clock.instant()
        cache[name]?.let { if (Duration.between(it.loadedAt, now) < CACHE_TTL) return it }
        val stored: StoredSecret? = guarded { repository.find(name) }
        val fresh = Cached(stored?.let { encryptor.decrypt(name, it.cipherText) }, stored?.updatedAt, now)
        cache[name] = fresh
        return fresh
    }

    private fun <T> guarded(action: () -> T): T = try {
        action()
    } catch (e: RuntimeException) {
        throw Unavailable("키 저장소(MySQL)에 연결할 수 없습니다", e)
    }

    private companion object {
        val CACHE_TTL: Duration = Duration.ofSeconds(30)
    }
}
