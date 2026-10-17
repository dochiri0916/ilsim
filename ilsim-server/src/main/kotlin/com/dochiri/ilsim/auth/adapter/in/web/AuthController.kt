package com.dochiri.ilsim.auth.adapter.`in`.web

import com.dochiri.ilsim.auth.application.port.`in`.AuthUseCase
import com.dochiri.ilsim.auth.application.usecase.AuthService
import com.dochiri.ilsim.auth.domain.exception.SetupAlreadyDoneException
import com.dochiri.ilsim.auth.domain.exception.DuplicateUsernameException
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

    /** user 가 없으면 로그인 전. setup 이 true 면 관리자 계정부터 만들어야 한다. */
    data class AuthState(val setup: Boolean, val user: String? = null)

    /** 로그인 상태와 최초 설정 필요 여부. 화면이 어떤 입력을 보여 줄지 정한다. */
    @GetMapping("/api/auth/me")
    fun me(req: HttpServletRequest): AuthState {
        val user = req.getSession(false)?.getAttribute(SESSION_USER) as? String
        return if (user != null) AuthState(false, user) else AuthState(auth.needsSetup())
    }

    @PostMapping("/api/auth/setup")
    fun setup(@RequestBody body: Credentials, req: HttpServletRequest): AuthState =
        start(req, auth.setup(body.username, body.password))

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
        return AuthState(false, user)
    }

    /** 로그인한 사용자만 부를 수 있다([SessionFilter]). */
    @GetMapping("/api/accounts")
    fun accounts(): List<String> = auth.usernames()

    @PostMapping("/api/accounts")
    fun createAccount(@RequestBody body: Credentials): Map<String, String> =
        mapOf("username" to auth.createAccount(body.username, body.password))

    @ExceptionHandler(InvalidAccountException::class)
    fun invalid(e: InvalidAccountException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.badRequest().body(mapOf("error" to e.message))

    @ExceptionHandler(SetupAlreadyDoneException::class, DuplicateUsernameException::class)
    fun conflict(e: RuntimeException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to e.message))

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
