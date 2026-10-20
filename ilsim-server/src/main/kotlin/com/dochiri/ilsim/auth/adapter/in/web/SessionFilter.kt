package com.dochiri.ilsim.auth.adapter.`in`.web

import com.dochiri.ilsim.auth.application.port.`in`.RememberMeUseCase
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

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
        val user = runCatching { remember.resolve(value) }.getOrElse { return false }
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
