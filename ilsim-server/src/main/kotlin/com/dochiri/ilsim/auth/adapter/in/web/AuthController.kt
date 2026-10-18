package com.dochiri.ilsim.auth.adapter.`in`.web

import com.dochiri.ilsim.auth.application.port.`in`.AuthUseCase
import com.dochiri.ilsim.auth.application.port.`in`.RememberMeUseCase
import com.dochiri.ilsim.auth.application.port.`in`.TwoFactorUseCase
import com.dochiri.ilsim.auth.application.usecase.AuthService
import com.dochiri.ilsim.auth.domain.exception.InvalidAccountException
import com.dochiri.ilsim.auth.domain.exception.LoginLockedException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.time.Duration
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * 로그인: 비밀번호 → (2단계 인증을 켠 계정이면) Google Authenticator 코드 → 세션.
 * "자동 로그인"을 고르면 30일짜리 토큰 쿠키를 주고, 그 기기는 세션이 끝나도 코드 없이 다시 들어온다([SessionFilter]).
 */
@RestController
class AuthController(
    private val auth: AuthUseCase,
    private val twoFactor: TwoFactorUseCase,
    private val remember: RememberMeUseCase,
    private val cookie: RememberCookie,
) {

    data class Credentials(val username: String? = null, val password: String? = null, val remember: Boolean = false)

    data class PasswordChange(val currentPassword: String? = null, val newPassword: String? = null)

    data class Code(val code: String? = null)

    /** user 가 없으면 로그인 전. [otpRequired]면 비밀번호는 맞았고 코드를 기다린다. */
    data class AuthState(val user: String? = null, val otpRequired: Boolean = false, val twoFactor: Boolean? = null)

    @GetMapping("/api/auth/me")
    fun me(req: HttpServletRequest): AuthState {
        val user = req.getSession(false)?.getAttribute(SESSION_USER) as? String
            ?: return AuthState(otpRequired = pending(req) != null)
        return AuthState(user, twoFactor = runCatching { twoFactor.enabled(user) }.getOrNull())
    }

    @PostMapping("/api/auth/login")
    fun login(@RequestBody body: Credentials, req: HttpServletRequest, res: HttpServletResponse): ResponseEntity<Any> {
        val user = auth.login(req.remoteAddr, body.username, body.password)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("error" to "아이디 또는 비밀번호가 맞지 않습니다"))
        if (twoFactor.enabled(user)) {
            req.getSession(false)?.invalidate()
            req.getSession(true).apply {
                setAttribute(PENDING_USER, user)
                setAttribute(PENDING_REMEMBER, body.remember)
                setAttribute(PENDING_AT, Instant.now())
            }
            return ResponseEntity.ok(AuthState(otpRequired = true))
        }
        return ResponseEntity.ok(complete(req, res, user, body.remember))
    }

    /** 로그인 두 번째 단계. 비밀번호를 맞힌 세션에서 [PENDING_TTL] 안에만 받는다. */
    @PostMapping("/api/auth/otp")
    fun otp(@RequestBody body: Code, req: HttpServletRequest, res: HttpServletResponse): ResponseEntity<Any> {
        val user = pending(req)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("error" to "다시 로그인하세요"))
        if (!twoFactor.verify(req.remoteAddr, user, body.code)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("error" to "코드가 맞지 않습니다"))
        }
        val keep = req.getSession(false)?.getAttribute(PENDING_REMEMBER) == true
        return ResponseEntity.ok(complete(req, res, user, keep))
    }

    @PostMapping("/api/auth/logout")
    fun logout(req: HttpServletRequest, res: HttpServletResponse): ResponseEntity<Void> {
        runCatching { remember.revoke(cookie.read(req)) }
        cookie.clear(res)
        req.getSession(false)?.invalidate()
        return ResponseEntity.noContent().build()
    }

    /** 2단계 인증 등록 시작: 새 비밀을 세션에만 두고 QR 용 URI 를 준다. */
    @PostMapping("/api/auth/totp/setup")
    fun totpSetup(req: HttpServletRequest): ResponseEntity<Any> {
        val user = sessionUser(req) ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val setup = twoFactor.setup(user)
        req.getSession(false)?.setAttribute(TOTP_SETUP, setup.secret)
        return ResponseEntity.ok(mapOf("secret" to setup.secret, "uri" to setup.uri))
    }

    @PostMapping("/api/auth/totp/enable")
    fun totpEnable(@RequestBody body: Code, req: HttpServletRequest): ResponseEntity<Any> {
        val user = sessionUser(req) ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val secret = req.getSession(false)?.getAttribute(TOTP_SETUP) as? String
            ?: return ResponseEntity.badRequest().body(mapOf("error" to "등록을 처음부터 다시 시작하세요"))
        if (!twoFactor.enable(req.remoteAddr, user, secret, body.code)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(mapOf("error" to "코드가 맞지 않습니다"))
        }
        req.getSession(false)?.removeAttribute(TOTP_SETUP)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/api/auth/totp/disable")
    fun totpDisable(@RequestBody body: Code, req: HttpServletRequest): ResponseEntity<Any> {
        val user = sessionUser(req) ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        if (!twoFactor.disable(req.remoteAddr, user, body.code)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(mapOf("error" to "코드가 맞지 않습니다"))
        }
        return ResponseEntity.noContent().build()
    }

    private fun sessionUser(req: HttpServletRequest) = req.getSession(false)?.getAttribute(SESSION_USER) as? String

    private fun pending(req: HttpServletRequest): String? {
        val session = req.getSession(false) ?: return null
        val at = session.getAttribute(PENDING_AT) as? Instant ?: return null
        if (Duration.between(at, Instant.now()) > PENDING_TTL) return null
        return session.getAttribute(PENDING_USER) as? String
    }

    private fun complete(req: HttpServletRequest, res: HttpServletResponse, user: String, keep: Boolean): AuthState {
        if (keep) {
            // 토큰 저장소(DB)가 안 되면 자동 로그인만 빠지고 로그인은 된다.
            runCatching { cookie.write(res, remember.issue(user)) }.onFailure { log.warn("자동 로그인 토큰을 만들지 못했습니다: {}", it.message) }
        }
        return start(req, user)
    }

    private fun start(req: HttpServletRequest, user: String): AuthState {
        req.getSession(false)?.invalidate() // 세션 고정 공격 방지: 로그인마다 새 세션
        req.getSession(true).setAttribute(SESSION_USER, user)
        return AuthState(user)
    }

    /** 로그인한 사용자가 자기 비밀번호를 바꾼다. 계정 생성은 관리자가 DB 에서 직접 한다. */
    @PostMapping("/api/auth/password")
    fun changePassword(@RequestBody body: PasswordChange, req: HttpServletRequest): ResponseEntity<Any> {
        val user = req.getSession(false)?.getAttribute(SESSION_USER) as? String
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        if (!auth.changePassword(req.remoteAddr, user, body.currentPassword, body.newPassword)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(mapOf("error" to "현재 비밀번호가 맞지 않습니다"))
        }
        start(req, user) // 비밀번호를 바꾸면 세션도 새로 시작하고 모든 기기의 자동 로그인을 끊는다
        runCatching { remember.revokeAll(user) }
        return ResponseEntity.noContent().build()
    }

    @ExceptionHandler(InvalidAccountException::class)
    fun invalid(e: InvalidAccountException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.badRequest().body(mapOf("error" to e.message))

    @ExceptionHandler(LoginLockedException::class)
    fun locked(e: LoginLockedException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(mapOf("error" to e.message))

    @ExceptionHandler(AuthService.Unavailable::class)
    fun unavailable(e: AuthService.Unavailable): ResponseEntity<Map<String, String?>> =
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(mapOf("error" to e.message))

    companion object {
        const val SESSION_USER = "ilsim.user"
        private const val PENDING_USER = "ilsim.pending.user"
        private const val PENDING_REMEMBER = "ilsim.pending.remember"
        private const val PENDING_AT = "ilsim.pending.at"
        private const val TOTP_SETUP = "ilsim.totp.setup"
        private val PENDING_TTL: Duration = Duration.ofMinutes(5)
        private val log = LoggerFactory.getLogger(AuthController::class.java)
    }
}
