package com.dochiri.ilsim.secret.adapter.`in`.web

import com.dochiri.ilsim.secret.api.SecretNames
import com.dochiri.ilsim.secret.api.SecretReader
import com.dochiri.ilsim.secret.application.port.`in`.ManageSecretUseCase
import com.dochiri.ilsim.secret.application.usecase.SecretService
import com.dochiri.ilsim.secret.domain.SecretStatus
import com.dochiri.ilsim.secret.domain.exception.InvalidSecretException
import java.time.Instant
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * 웹에서 JEV API 키를 등록·삭제한다. 키 값은 응답에 담지 않는다(마지막 4자리만).
 * Runner 는 작업을 받을 때 `/runner/api/secrets/jev` 로 키를 받아 메모리에만 두고 쓴다.
 */
@RestController
class SettingsController(
    private val secrets: ManageSecretUseCase,
    private val reader: SecretReader,
) {
    data class KeyRequest(val key: String? = null)

    data class JevSetting(val configured: Boolean, val last4: String?, val updatedAt: Instant?)

    data class SettingsResponse(val jev: JevSetting)

    @GetMapping("/api/settings")
    fun settings(): SettingsResponse = SettingsResponse(secrets.status(SecretNames.JEV_API_KEY).toSetting())

    @PutMapping("/api/settings/jev-key")
    fun put(@RequestBody request: KeyRequest): SettingsResponse {
        secrets.put(SecretNames.JEV_API_KEY, request.key?.trim())
        return settings()
    }

    @DeleteMapping("/api/settings/jev-key")
    fun delete(): SettingsResponse {
        secrets.delete(SecretNames.JEV_API_KEY)
        return settings()
    }

    @GetMapping("/runner/api/secrets/jev")
    fun runnerKey(): ResponseEntity<Map<String, String>> {
        val key = reader.get(SecretNames.JEV_API_KEY) ?: return ResponseEntity.noContent().build()
        return ResponseEntity.ok(mapOf("key" to key))
    }

    @ExceptionHandler(SecretService.Unavailable::class)
    fun unavailable(e: SecretService.Unavailable): ResponseEntity<Map<String, String?>> =
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(mapOf("error" to e.message))

    @ExceptionHandler(InvalidSecretException::class)
    fun invalid(e: InvalidSecretException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.badRequest().body(mapOf("error" to e.message))

    private fun SecretStatus.toSetting() = JevSetting(configured, last4, updatedAt)
}
