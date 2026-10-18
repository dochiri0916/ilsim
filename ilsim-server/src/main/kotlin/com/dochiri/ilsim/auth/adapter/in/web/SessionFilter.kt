package com.dochiri.ilsim.auth.adapter.`in`.web

import com.dochiri.ilsim.auth.application.port.`in`.RememberMeUseCase
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 웹(/api 하위) 접근은 로그인 세션이 있어야 한다. 상태를 바꾸는 요청은 커스텀 헤더를 요구해
 * 다른 사이트의 폼/스크립트가 쿠키만으로 요청을 보내지 못하게 한다(SameSite 쿠키와 함께 CSRF 방어).
 * 세션이 없고 자동 로그인 쿠키가 유효하면 새 세션을 만든다.
 */
@Component
class SessionFilter(private val remember: RememberMeUseCase, private val cookie: RememberCookie) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !request.requestURI.startsWith("/api/")

    override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
        if (req.method !in SAFE_METHODS && req.getHeader(CSRF_HEADER) != CSRF_VALUE) {
            res.sendError(HttpServletResponse.SC_FORBIDDEN)
            return
        }
        val loggedIn = req.getSession(false)?.getAttribute(AuthController.SESSION_USER) != null || restore(req, res)
        if (req.requestURI in PUBLIC || loggedIn) chain.doFilter(req, res) else res.sendError(HttpServletResponse.SC_UNAUTHORIZED)
    }

    private fun restore(req: HttpServletRequest, res: HttpServletResponse): Boolean {
        val value = cookie.read(req) ?: return false
        val user = runCatching { remember.resolve(value) }.getOrElse { return false } // DB 가 안 되면 쿠키는 두고 다음에 다시 본다
        if (user == null) {
            cookie.clear(res)
            return false
        }
        req.getSession(false)?.invalidate()
        req.getSession(true).setAttribute(AuthController.SESSION_USER, user)
        return true
    }

    private companion object {
        const val CSRF_HEADER = "X-Requested-With"
        const val CSRF_VALUE = "ilsim"
        val PUBLIC = setOf("/api/status", "/api/auth/me", "/api/auth/login", "/api/auth/otp")
        val SAFE_METHODS = setOf("GET", "HEAD", "OPTIONS")
    }
}
