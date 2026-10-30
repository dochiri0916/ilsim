package com.dochiri.ilsim.auth

import com.dochiri.ilsim.auth.adapter.`in`.web.RunnerTokenFilter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
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
        assertThat(status("/runner/api/tasks")).isEqualTo(401)
        assertThat(status("/mcp")).isEqualTo(401)
        assertThat(status("/mcp", "wrong")).isEqualTo(401)
        assertThat(status("/mcp", "secret")).isEqualTo(200)
        assertThat(status("/runner/api/tasks", "secret")).isEqualTo(200)
        assertThat(status("/api/status")).isEqualTo(200)
    }
}
