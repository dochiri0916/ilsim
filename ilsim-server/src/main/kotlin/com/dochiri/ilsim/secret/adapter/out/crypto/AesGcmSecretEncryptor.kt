package com.dochiri.ilsim.secret.adapter.out.crypto

import com.dochiri.ilsim.secret.application.port.out.SecretEncryptor
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.slf4j.LoggerFactory

/**
 * 비밀 값을 AES-256-GCM 으로 암호화한다. 암호문은 `base64(iv ‖ 암호문 ‖ 태그)` 이고, 이름을 추가 인증 데이터로 묶는다.
 * 마스터 키는 DB 에 두지 않는다: 환경 변수 ILSIM_SECRET_KEY(아무 문자열, SHA-256 으로 32바이트화)가 있으면 그것을,
 * 없으면 `~/.ilsim/server-secret.key` 를 쓰고 처음에 만든다.
 */
class AesGcmSecretEncryptor(masterKey: ByteArray) : SecretEncryptor {

    private val key: SecretKeySpec

    init {
        require(masterKey.size == 32) { "마스터 키는 32바이트여야 합니다" }
        key = SecretKeySpec(masterKey, "AES")
    }

    override fun encrypt(name: String, plain: String): String {
        try {
            val iv = ByteArray(IV_BYTES).also(RANDOM::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(name.toByteArray())
            return Base64.getEncoder().encodeToString(iv + cipher.doFinal(plain.toByteArray()))
        } catch (e: GeneralSecurityException) {
            throw IllegalStateException("암호화에 실패했습니다", e)
        }
    }

    override fun decrypt(name: String, stored: String): String {
        try {
            val input = Base64.getDecoder().decode(stored)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, input, 0, IV_BYTES))
            cipher.updateAAD(name.toByteArray())
            return String(cipher.doFinal(input, IV_BYTES, input.size - IV_BYTES))
        } catch (e: GeneralSecurityException) {
            throw corrupted(e)
        } catch (e: IllegalArgumentException) {
            throw corrupted(e)
        }
    }

    private fun corrupted(cause: Exception) =
        IllegalStateException("복호화에 실패했습니다. 마스터 키가 바뀌었거나 데이터가 손상되었습니다", cause)

    companion object {
        private val log = LoggerFactory.getLogger(AesGcmSecretEncryptor::class.java)
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private val RANDOM = SecureRandom()

        fun fromEnvironment(env: (String) -> String? = System::getenv, home: Path = Path.of(System.getProperty("user.home"))): AesGcmSecretEncryptor =
            AesGcmSecretEncryptor(loadMasterKey(env, home))

        private fun loadMasterKey(env: (String) -> String?, home: Path): ByteArray {
            val configured = env("ILSIM_SECRET_KEY")
            try {
                if (!configured.isNullOrBlank()) return MessageDigest.getInstance("SHA-256").digest(configured.toByteArray())
                val file = home.resolve(".ilsim").resolve("server-secret.key")
                if (Files.exists(file)) return Base64.getDecoder().decode(Files.readString(file).trim())
                val generated = ByteArray(32).also(RANDOM::nextBytes)
                Files.createDirectories(file.parent)
                Files.writeString(file, Base64.getEncoder().encodeToString(generated))
                try {
                    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
                } catch (_: UnsupportedOperationException) {
                    // POSIX 가 아닌 파일 시스템
                }
                log.info("암호화 마스터 키를 새로 만들었습니다: {} (잃어버리면 저장된 키를 복호화할 수 없으니 백업하세요)", file)
                return generated
            } catch (e: IOException) {
                throw IllegalStateException("마스터 키를 준비하지 못했습니다", e)
            } catch (e: GeneralSecurityException) {
                throw IllegalStateException("마스터 키를 준비하지 못했습니다", e)
            }
        }
    }
}
