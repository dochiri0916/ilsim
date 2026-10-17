package com.dochiri.ilsim.auth.adapter.`in`.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 웹(/api 하위) 접근은 로그인 세션이 있어야 한다. 상태를 바꾸는 요청은 커스텀 헤더를 요구해
 * 다른 사이트의 폼/스크립트가 쿠키만으로 요청을 보내지 못하게 한다(SameSite 쿠키와 함께 CSRF 방어).
 */
@Component
class SessionFilter : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !request.requestURI.startsWith("/api/")

    override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
        if (req.method !in SAFE_METHODS && req.getHeader(CSRF_HEADER) != CSRF_VALUE) {
            res.sendError(HttpServletResponse.SC_FORBIDDEN)
            return
        }
        val loggedIn = req.getSession(false)?.getAttribute(AuthController.SESSION_USER) != null
        if (req.requestURI in PUBLIC || loggedIn) chain.doFilter(req, res) else res.sendError(HttpServletResponse.SC_UNAUTHORIZED)
    }

    private companion object {
        const val CSRF_HEADER = "X-Requested-With"
        const val CSRF_VALUE = "ilsim"
        val PUBLIC = setOf("/api/status", "/api/auth/me", "/api/auth/setup", "/api/auth/login")
        val SAFE_METHODS = setOf("GET", "HEAD", "OPTIONS")
    }
}
