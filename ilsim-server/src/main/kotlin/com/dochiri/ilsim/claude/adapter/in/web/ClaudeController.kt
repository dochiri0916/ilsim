package com.dochiri.ilsim.claude.adapter.`in`.web

import com.dochiri.ilsim.claude.application.port.`in`.ClaudeUseCase
import com.dochiri.ilsim.claude.domain.ClaudeAccount
import com.dochiri.ilsim.claude.domain.ClaudeEnvironment
import com.dochiri.ilsim.claude.domain.LoginSession
import com.dochiri.ilsim.claude.domain.exception.InvalidClaudeRequestException
import java.time.Instant
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class ClaudeController(private val claude: ClaudeUseCase) {

    data class EnvironmentRequest(val claudeMd: String? = null, val settingsJson: String? = null)

    data class EnvironmentResponse(val claudeMd: String, val settingsJson: String, val version: String, val updatedAt: Instant?) {
        companion object {
            fun from(e: ClaudeEnvironment) = EnvironmentResponse(e.claudeMd, e.settingsJson, e.version, e.updatedAt)
        }
    }

    data class LoginResponse(val id: String, val runnerId: String, val name: String, val status: String, val requestedAt: Instant,
        val url: String?, val error: String?, val email: String?, val plan: String?) {
        companion object {
            fun from(s: LoginSession) = LoginResponse(s.id, s.runnerId, s.name, s.status.name, s.requestedAt, s.url, s.error, s.email, s.plan)
        }
    }

    data class AccountsResponse(val accounts: List<ClaudeAccount>, val logins: List<LoginResponse>)

    data class StartLoginRequest(val runnerId: String = "", val name: String? = null)

    data class CodeRequest(val code: String? = null)

    data class ReportedAccount(val name: String = "", val loggedIn: Boolean = false, val email: String? = null, val plan: String? = null)

    data class AccountReport(val runnerId: String = "", val accounts: List<ReportedAccount> = emptyList())

    data class RunnerRequest(val runnerId: String = "")

    data class UrlRequest(val url: String? = null)

    data class LoginResult(val loggedIn: Boolean = false, val email: String? = null, val plan: String? = null, val error: String? = null)

    @GetMapping("/api/claude/environment")
    fun environment(): EnvironmentResponse = EnvironmentResponse.from(claude.environment())

    @PutMapping("/api/claude/environment")
    fun saveEnvironment(@RequestBody request: EnvironmentRequest): EnvironmentResponse =
        EnvironmentResponse.from(claude.saveEnvironment(request.claudeMd, request.settingsJson))

    @GetMapping("/api/claude/accounts")
    fun accounts(): AccountsResponse = AccountsResponse(claude.accounts(), claude.logins().map(LoginResponse::from))

    @PostMapping("/api/claude/logins")
    fun startLogin(@RequestBody request: StartLoginRequest): LoginResponse = LoginResponse.from(claude.startLogin(request.runnerId, request.name))

    @PostMapping("/api/claude/logins/{id}/code")
    fun submitCode(@PathVariable id: String, @RequestBody request: CodeRequest): LoginResponse = LoginResponse.from(claude.submitCode(id, request.code))

    @DeleteMapping("/api/claude/accounts/{runnerId}/{name}")
    fun remove(@PathVariable runnerId: String, @PathVariable name: String): ResponseEntity<Void> {
        claude.removeAccount(runnerId, name)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/runner/api/claude/environment")
    fun runnerEnvironment(): EnvironmentResponse = environment()

    @PostMapping("/runner/api/claude/accounts")
    fun report(@RequestBody request: AccountReport): List<String> {
        claude.reportAccounts(request.runnerId, request.accounts.map { ClaudeAccount(request.runnerId, it.name, it.loggedIn, it.email, it.plan) })
        return claude.claimRemovals(request.runnerId)
    }

    @PostMapping("/runner/api/claude/logins/claim")
    fun claim(@RequestBody request: RunnerRequest): ResponseEntity<LoginResponse> =
        claude.claimLogin(request.runnerId)?.let { ResponseEntity.ok(LoginResponse.from(it)) } ?: ResponseEntity.noContent().build()

    @PostMapping("/runner/api/claude/logins/{id}/url")
    fun url(@PathVariable id: String, @RequestBody request: UrlRequest): LoginResponse = LoginResponse.from(claude.openLogin(id, request.url))

    @PostMapping("/runner/api/claude/logins/{id}/code")
    fun code(@PathVariable id: String): ResponseEntity<Map<String, String>> =
        claude.takeCode(id)?.let { ResponseEntity.ok(mapOf("code" to it)) } ?: ResponseEntity.noContent().build()

    @PostMapping("/runner/api/claude/logins/{id}/result")
    fun result(@PathVariable id: String, @RequestBody request: LoginResult): LoginResponse =
        LoginResponse.from(claude.finishLogin(id, request.loggedIn, request.email, request.plan, request.error))

    @ExceptionHandler(InvalidClaudeRequestException::class)
    fun invalid(e: InvalidClaudeRequestException): ResponseEntity<Map<String, String?>> = ResponseEntity.badRequest().body(mapOf("error" to e.message))
}
