package com.dochiri.ilsim.jev.adapter.out.secret

import com.dochiri.ilsim.jev.application.port.out.ApiKeySource
import com.dochiri.ilsim.secret.api.SecretNames
import com.dochiri.ilsim.secret.api.SecretReader
import org.slf4j.LoggerFactory

class SecretApiKeySource(
    private val secrets: SecretReader,
    private val env: (String) -> String? = System::getenv,
) : ApiKeySource {

    override fun apiKey(): String? {
        val stored = try {
            secrets.get(SecretNames.JEV_API_KEY)
        } catch (e: RuntimeException) {
            log.warn("JEV 키 저장소를 읽지 못했습니다: {}", e.message)
            null
        }
        return listOf(stored, env("TYPESAFE_API_KEY"), env("JEV_API_KEY")).firstOrNull { !it.isNullOrBlank() }?.trim()
    }

    private companion object {
        val log = LoggerFactory.getLogger(SecretApiKeySource::class.java)
    }
}
