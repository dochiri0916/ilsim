package com.dochiri.ilsim.auth

import com.dochiri.ilsim.auth.adapter.`in`.web.RunnerTokenFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class RunnerTokenFilterTest {
    private val filter = RunnerTokenFilter("secret", "")

    private fun status(uri: String, token: String? = null): Int {
        val req = MockHttpServletRequest("POST", uri).apply { token?.let { addHeader("X-Ilsim-Token", it) } }
        val res = MockHttpServletResponse()
        filter.doFilter(req, res, MockFilterChain())
        return res.status
    }

    @Test
    fun `Runner API와 MCP 엔드포인트는 토큰이 맞아야 통과한다`() {
        assertEquals(401, status("/runner/api/tasks"))
        assertEquals(401, status("/mcp"))
        assertEquals(401, status("/mcp", "wrong"))
        assertEquals(200, status("/mcp", "secret"))
        assertEquals(200, status("/runner/api/tasks", "secret"))
        assertEquals(200, status("/api/status"))
    }
}
