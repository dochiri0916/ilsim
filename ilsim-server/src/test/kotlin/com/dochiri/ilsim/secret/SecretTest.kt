package com.dochiri.ilsim.secret

import com.dochiri.ilsim.jev.adapter.out.secret.SecretApiKeySource
import com.dochiri.ilsim.jev.adapter.out.typesafe.TypeSafeJevGateway
import com.dochiri.ilsim.secret.adapter.`in`.web.SettingsController
import com.dochiri.ilsim.secret.adapter.out.crypto.AesGcmSecretEncryptor
import com.dochiri.ilsim.secret.api.SecretNames
import com.dochiri.ilsim.secret.application.port.out.SecretRepository
import com.dochiri.ilsim.secret.application.port.out.StoredSecret
import com.dochiri.ilsim.secret.application.usecase.SecretService
import com.dochiri.ilsim.secret.domain.exception.InvalidSecretException
import java.time.Clock
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SecretTest {

    private fun key(seed: Int) = ByteArray(32) { (it + seed).toByte() }

    private class MemoryRepository : SecretRepository {
        val rows = HashMap<String, StoredSecret>()
        var down = false

        override fun find(name: String): StoredSecret? {
            check(!down) { "db down" }
            return rows[name]
        }

        override fun save(name: String, cipherText: String, updatedAt: Instant) {
            check(!down) { "db down" }
            rows[name] = StoredSecret(cipherText, updatedAt)
        }

        override fun delete(name: String) {
            rows.remove(name)
        }
    }

    private fun service(repo: MemoryRepository = MemoryRepository()) =
        SecretService(repo, AesGcmSecretEncryptor(key(1)), Clock.systemUTC())

    @Test
    fun `암호화한 값은 평문이 드러나지 않고 같은 이름과 키로만 복호화된다`() {
        val encryptor = AesGcmSecretEncryptor(key(1))
        val stored = encryptor.encrypt("jev-api-key", "tsk_live_abcdef123456")

        assertThat(stored).doesNotContain("tsk_live").doesNotContain("abcdef123456")
        assertThat(encryptor.decrypt("jev-api-key", stored)).isEqualTo("tsk_live_abcdef123456")
        assertThat(encryptor.encrypt("jev-api-key", "tsk_live_abcdef123456")).isNotEqualTo(stored)

        assertThatThrownBy { encryptor.decrypt("other-name", stored) }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { AesGcmSecretEncryptor(key(2)).decrypt("jev-api-key", stored) }.isInstanceOf(IllegalStateException::class.java)
        val tampered = stored.toCharArray().also { it[stored.length / 2] = if (it[stored.length / 2] == 'A') 'B' else 'A' }
        assertThatThrownBy { encryptor.decrypt("jev-api-key", String(tampered)) }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `저장소에는 암호문만 가고 상태에는 마지막 네 자리만 나온다`() {
        val repo = MemoryRepository()
        val service = service(repo)
        assertThat(service.status(SecretNames.JEV_API_KEY).configured).isFalse()

        service.put(SecretNames.JEV_API_KEY, "tsk_live_abcdef123456")

        assertThat(repo.rows.getValue(SecretNames.JEV_API_KEY).cipherText).doesNotContain("abcdef")
        val status = service.status(SecretNames.JEV_API_KEY)
        assertThat(status.configured).isTrue()
        assertThat(status.last4).isEqualTo("3456")
        assertThat(status.updatedAt).isNotNull()
        assertThat(service.get(SecretNames.JEV_API_KEY)).isEqualTo("tsk_live_abcdef123456")

        service.delete(SecretNames.JEV_API_KEY)
        assertThat(service.get(SecretNames.JEV_API_KEY)).isNull()
    }

    @Test
    fun `잘못된 키 형식은 거절하고 DB 가 없으면 사용 불가로 알린다`() {
        val repo = MemoryRepository()
        val service = service(repo)
        assertThatThrownBy { service.put("jev-api-key", " ") }.isInstanceOf(InvalidSecretException::class.java)
        assertThatThrownBy { service.put("jev-api-key", "has space") }.isInstanceOf(InvalidSecretException::class.java)
        assertThatThrownBy { service.put("jev-api-key", "x".repeat(600)) }.isInstanceOf(InvalidSecretException::class.java)

        repo.down = true
        assertThatThrownBy { service.put("jev-api-key", "valid-key") }.isInstanceOf(SecretService.Unavailable::class.java)
        assertThatThrownBy { service.status("jev-api-key") }.isInstanceOf(SecretService.Unavailable::class.java)
    }

    @Test
    fun `설정 응답에는 키 값이 없고 Runner 용 조회만 값을 돌려준다`() {
        val service = service()
        val controller = SettingsController(service, service)
        assertThat(controller.runnerKey().statusCode.value()).isEqualTo(204)

        val response = controller.put(SettingsController.KeyRequest("  tsk_live_abcdef123456 "))
        assertThat(response.toString()).contains("3456").doesNotContain("tsk_live")
        assertThat(controller.runnerKey().body).containsEntry("key", "tsk_live_abcdef123456")

        controller.delete()
        assertThat(controller.settings().jev.configured).isFalse()
    }

    @Test
    fun `저장한 키가 있으면 JEV 가 켜지고 지우면 꺼진다`() {
        val service = service()
        val gateway = TypeSafeJevGateway(SecretApiKeySource(service) { null })
        assertThat(gateway.enabled()).isFalse()
        service.put(SecretNames.JEV_API_KEY, "tsk_live_abcdef123456")
        assertThat(gateway.enabled()).isTrue()
        service.delete(SecretNames.JEV_API_KEY)
        assertThat(gateway.enabled()).isFalse()
        gateway.close()
    }
}
