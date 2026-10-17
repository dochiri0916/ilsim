package com.dochiri.ilsim.auth.adapter.`in`.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.HexFormat
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/** Runner 전용 API(/runner 하위) 토큰 인증. 사람이 쓰는 /api 하위 는 세션 로그인([SessionFilter])이 맡는다. */
@Component
class RunnerTokenFilter(@Value("\${ilsim.token:}") configured: String) : OncePerRequestFilter() {

    private val token: ByteArray = configured.ifBlank { generate() }.toByteArray()

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !request.requestURI.startsWith("/runner/")

    override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
        val given = req.getHeader("X-Ilsim-Token")
        if (given != null && MessageDigest.isEqual(given.toByteArray(), token)) chain.doFilter(req, res) else res.sendError(HttpServletResponse.SC_UNAUTHORIZED)
    }

    private companion object {
        val log = LoggerFactory.getLogger(RunnerTokenFilter::class.java)

        fun generate(): String {
            val bytes = ByteArray(16).also(SecureRandom()::nextBytes)
            val token = HexFormat.of().formatHex(bytes)
            log.warn("ILSIM_TOKEN 미설정 - 임시 토큰 생성: {}", token)
            return token
        }
    }
}
