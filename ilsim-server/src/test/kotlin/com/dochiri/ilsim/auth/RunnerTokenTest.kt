package com.dochiri.ilsim.auth

import com.dochiri.ilsim.auth.adapter.`in`.web.RunnerTokenFile
import com.dochiri.ilsim.auth.adapter.`in`.web.RunnerTokenFilter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class RunnerTokenTest {

    @TempDir
    lateinit var home: Path

    @Test
    fun `토큰 파일이 없으면 소유자만 읽는 파일로 만들고 다시 부르면 같은 값이다`() {
        val file = home.resolve("nested/token")
        val first = RunnerTokenFile.loadOrCreate(file)
        assertThat(first).hasSizeGreaterThanOrEqualTo(32)
        assertThat(Files.readString(file)).isEqualTo(first)
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------")
        assertThat(RunnerTokenFile.loadOrCreate(file)).isEqualTo(first)
    }

    @Test
    fun `비어 있는 파일은 새 토큰으로 채운다`() {
        val file = home.resolve("token")
        Files.writeString(file, "\n")
        val token = RunnerTokenFile.loadOrCreate(file)
        assertThat(token).isNotBlank()
        assertThat(Files.readString(file).trim()).isEqualTo(token)
    }

    private fun status(filter: RunnerTokenFilter, uri: String, header: String?): Int {
        val req = MockHttpServletRequest("GET", uri).also { r -> header?.let { r.addHeader("X-Ilsim-Token", it) } }
        val res = MockHttpServletResponse()
        filter.doFilter(req, res, MockFilterChain())
        return res.status
    }

    @Test
    fun `설정한 환경 변수 토큰이 있으면 그 값을 쓰고 파일은 만들지 않는다`() {
        val filter = RunnerTokenFilter("fixed-token", home.toString())
        assertThat(status(filter, "/runner/api/poll", "fixed-token")).isEqualTo(200)
        assertThat(status(filter, "/runner/api/poll", "other")).isEqualTo(401)
        assertThat(Files.exists(home.resolve("token"))).isFalse()
    }

    @Test
    fun `설정이 없으면 파일 토큰으로 인증하고 재시작해도 같은 토큰이다`() {
        val filter = RunnerTokenFilter("", home.toString())
        val token = Files.readString(home.resolve("token")).trim()
        assertThat(status(filter, "/runner/api/poll", token)).isEqualTo(200)
        assertThat(status(filter, "/runner/api/poll", null)).isEqualTo(401)
        assertThat(status(filter, "/api/tasks", null)).isEqualTo(200) // /api 는 이 필터의 대상이 아니다
        assertThat(status(RunnerTokenFilter("", home.toString()), "/runner/api/poll", token)).isEqualTo(200)
    }
}
