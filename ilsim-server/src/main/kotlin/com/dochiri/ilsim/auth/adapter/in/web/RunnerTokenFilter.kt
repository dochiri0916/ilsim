package com.dochiri.ilsim.auth.adapter.`in`.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.security.MessageDigest
import java.nio.file.Path
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/** Runner 전용 API(/runner 하위) 토큰 인증. 사람이 쓰는 /api 하위 는 세션 로그인([SessionFilter])이 맡는다. */
@Component
class RunnerTokenFilter(
    @Value("\${ilsim.token:}") configured: String,
    @Value("\${ILSIM_HOME:\${user.home}/.ilsim}") home: String,
) : OncePerRequestFilter() {

    /** 환경 변수 ILSIM_TOKEN 이 있으면 그 값, 없으면 `<ILSIM_HOME>/token` 파일(없으면 만든다). Runner 도 같은 순서로 찾는다. */
    private val token: ByteArray = configured.ifBlank { RunnerTokenFile.loadOrCreate(Path.of(home, "token")) }.toByteArray()

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !request.requestURI.startsWith("/runner/")

    override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
        val given = req.getHeader("X-Ilsim-Token")
        if (given != null && MessageDigest.isEqual(given.toByteArray(), token)) chain.doFilter(req, res) else res.sendError(HttpServletResponse.SC_UNAUTHORIZED)
    }
}
