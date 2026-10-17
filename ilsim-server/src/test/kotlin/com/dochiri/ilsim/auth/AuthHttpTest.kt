package com.dochiri.ilsim.auth

import com.dochiri.ilsim.auth.adapter.`in`.web.AuthController
import com.dochiri.ilsim.auth.adapter.`in`.web.SessionFilter
import com.dochiri.ilsim.auth.adapter.out.security.BcryptPasswordHasher
import com.dochiri.ilsim.auth.application.port.out.AccountRepository
import com.dochiri.ilsim.auth.application.usecase.AuthService
import com.dochiri.ilsim.auth.domain.Account
import com.dochiri.ilsim.auth.domain.LoginThrottle
import java.time.Clock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/** 로그인·비밀번호 변경이 HTTP 에서 세션과 CSRF 헤더 규칙을 지키는지 본다. */
class AuthHttpTest {

    private class Memory : AccountRepository {
        val accounts = LinkedHashMap<String, Account>()
        override fun findByUsername(username: String): Account? = accounts[username]
        override fun updatePasswordHash(username: String, passwordHash: String): Boolean {
            if (username !in accounts) return false
            accounts[username] = Account(username, passwordHash)
            return true
        }
    }

    private val hasher = BcryptPasswordHasher()
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setUp() {
        val repo = Memory().also { it.accounts["admin"] = Account("admin", hasher.hash("correct-horse")) }
        val auth = AuthService(repo, hasher, Clock.systemUTC())
        mvc = MockMvcBuilders.standaloneSetup(AuthController(auth)).addFilters<org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder>(SessionFilter()).build()
    }

    private fun MockHttpServletRequestBuilder.csrf() = header("X-Requested-With", "ilsim")

    private fun json(url: String, body: String, session: MockHttpSession? = null) =
        post(url).csrf().contentType(MediaType.APPLICATION_JSON).content(body).also { if (session != null) it.session(session) }

    /** 로그인마다 새 세션이 만들어지므로(세션 고정 방지) 응답 요청에 붙은 세션을 쓴다. */
    private fun sessionOf(result: MvcResult): MockHttpSession = result.request.getSession(false) as MockHttpSession

    private fun login(password: String = "correct-horse"): MockHttpSession {
        return sessionOf(mvc.perform(json("/api/auth/login", """{"username":"admin","password":"$password"}""")).andReturn())
    }

    @Test
    fun `로그인 전에는 me 가 사용자 없이 응답하고 보호된 경로는 401 이다`() {
        mvc.perform(get("/api/auth/me")).andExpect(status().isOk).andExpect(jsonPath("$.user").doesNotExist())
        mvc.perform(get("/api/tasks")).andExpect(status().isUnauthorized)
        mvc.perform(post("/api/auth/password").csrf().contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `상태를 바꾸는 요청에 CSRF 헤더가 없으면 403 이다`() {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""{"username":"admin","password":"correct-horse"}"""))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `맞는 자격으로 로그인하면 세션이 생기고 틀리면 401 이다`() {
        mvc.perform(json("/api/auth/login", """{"username":"admin","password":"wrong"}""")).andExpect(status().isUnauthorized)
        val result = mvc.perform(json("/api/auth/login", """{"username":"admin","password":"correct-horse"}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.user").value("admin")).andReturn()
        val session = sessionOf(result)
        mvc.perform(get("/api/auth/me").session(session)).andExpect(jsonPath("$.user").value("admin"))
    }

    @Test
    fun `가입과 계정 추가 경로는 없다`() {
        val session = login()
        mvc.perform(json("/api/auth/setup", """{"username":"newbie","password":"another-pass"}""", session)).andExpect(status().isNotFound)
        mvc.perform(json("/api/accounts", """{"username":"newbie","password":"another-pass"}""", session)).andExpect(status().isNotFound)
    }

    @Test
    fun `비밀번호를 바꾸면 204 이고 이후 새 비밀번호로만 로그인된다`() {
        val session = login()
        mvc.perform(json("/api/auth/password", """{"currentPassword":"correct-horse","newPassword":"brand-new-pass"}""", session))
            .andExpect(status().isNoContent)
        mvc.perform(json("/api/auth/login", """{"username":"admin","password":"correct-horse"}""")).andExpect(status().isUnauthorized)
        mvc.perform(json("/api/auth/login", """{"username":"admin","password":"brand-new-pass"}""")).andExpect(status().isOk)
    }

    @Test
    fun `현재 비밀번호가 틀리면 403, 새 비밀번호가 짧으면 400 이다`() {
        val session = login()
        mvc.perform(json("/api/auth/password", """{"currentPassword":"wrong","newPassword":"brand-new-pass"}""", session))
            .andExpect(status().isForbidden).andExpect(jsonPath("$.error").exists())
        mvc.perform(json("/api/auth/password", """{"currentPassword":"correct-horse","newPassword":"short"}""", session))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `로그인 실패가 쌓이면 429 로 잠긴다`() {
        repeat(LoginThrottle.MAX_FAILURES) { mvc.perform(json("/api/auth/login", """{"username":"admin","password":"wrong"}""")) }
        mvc.perform(json("/api/auth/login", """{"username":"admin","password":"correct-horse"}""")).andExpect(status().isTooManyRequests)
    }

    @Test
    fun `로그아웃하면 세션이 끝나 보호된 경로가 다시 401 이다`() {
        val session = login()
        mvc.perform(post("/api/auth/logout").csrf().session(session)).andExpect(status().isNoContent)
        mvc.perform(get("/api/tasks").session(session)).andExpect(status().isUnauthorized)
    }
}
