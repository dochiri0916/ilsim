package com.dochiri.ilsim.auth.adapter.`in`.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.security.MessageDigest
import java.nio.file.Path
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class RunnerTokenFilter(
    @Value("\${ilsim.token:}") configured: String,
    @Value("\${ILSIM_HOME:\${user.home}/.ilsim}") home: String,
) : OncePerRequestFilter() {

    private val token: ByteArray = configured.ifBlank { RunnerTokenFile.loadOrCreate(Path.of(home, "token")) }.toByteArray()

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = PROTECTED.none { request.requestURI.startsWith(it) }

    override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
        val given = req.getHeader("X-Ilsim-Token")
        if (given != null && MessageDigest.isEqual(given.toByteArray(), token)) chain.doFilter(req, res) else res.sendError(HttpServletResponse.SC_UNAUTHORIZED)
    }

    companion object {
        private val PROTECTED = listOf("/runner/", "/mcp")
    }
}
