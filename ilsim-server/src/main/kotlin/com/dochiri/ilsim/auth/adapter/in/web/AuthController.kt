package com.dochiri.ilsim.auth.adapter.`in`.web

import com.dochiri.ilsim.auth.application.port.`in`.AuthUseCase
import com.dochiri.ilsim.auth.application.usecase.AuthService
import com.dochiri.ilsim.auth.domain.exception.InvalidAccountException
import com.dochiri.ilsim.auth.domain.exception.LoginLockedException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class AuthController(private val auth: AuthUseCase) {

    data class Credentials(val username: String? = null, val password: String? = null)

    data class PasswordChange(val currentPassword: String? = null, val newPassword: String? = null)

    /** user 가 없으면 로그인 전. */
    data class AuthState(val user: String? = null)

    @GetMapping("/api/auth/me")
    fun me(req: HttpServletRequest): AuthState = AuthState(req.getSession(false)?.getAttribute(SESSION_USER) as? String)

    @PostMapping("/api/auth/login")
    fun login(@RequestBody body: Credentials, req: HttpServletRequest): ResponseEntity<Any> {
        val user = auth.login(req.remoteAddr, body.username, body.password)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("error" to "아이디 또는 비밀번호가 맞지 않습니다"))
        return ResponseEntity.ok(start(req, user))
    }

    @PostMapping("/api/auth/logout")
    fun logout(req: HttpServletRequest): ResponseEntity<Void> {
        req.getSession(false)?.invalidate()
        return ResponseEntity.noContent().build()
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
        start(req, user) // 비밀번호를 바꾸면 세션도 새로 시작한다
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
    }
}
