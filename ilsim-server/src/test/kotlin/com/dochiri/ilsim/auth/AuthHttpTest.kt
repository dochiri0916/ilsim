package com.dochiri.ilsim.auth

import com.dochiri.ilsim.auth.adapter.`in`.web.AuthController
import com.dochiri.ilsim.auth.adapter.`in`.web.RememberCookie
import com.dochiri.ilsim.auth.adapter.`in`.web.SessionFilter
import com.dochiri.ilsim.auth.adapter.out.security.BcryptPasswordHasher
import com.dochiri.ilsim.auth.application.port.out.AccountRepository
import com.dochiri.ilsim.auth.application.port.out.LoginTokenRepository
import com.dochiri.ilsim.auth.application.port.out.TotpSecretStore
import com.dochiri.ilsim.auth.application.usecase.AuthService
import com.dochiri.ilsim.auth.application.usecase.RememberMeService
import com.dochiri.ilsim.auth.application.usecase.TwoFactorService
import com.dochiri.ilsim.auth.domain.Account
import com.dochiri.ilsim.auth.domain.LoginThrottle
import com.dochiri.ilsim.auth.domain.LoginToken
import com.dochiri.ilsim.auth.domain.Totp
import jakarta.servlet.http.Cookie
import java.time.Instant
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

    private class Tokens : LoginTokenRepository {
        val tokens = LinkedHashMap<String, LoginToken>()
        override fun save(token: LoginToken) { tokens[token.series] = token }
        override fun find(series: String) = tokens[series]
        override fun delete(series: String) { tokens.remove(series) }
        override fun deleteByUsername(username: String) { tokens.values.removeIf { it.username == username } }
    }

    private class Secrets : TotpSecretStore {
        val secrets = LinkedHashMap<String, String>()
        override fun find(username: String) = secrets[username]
        override fun save(username: String, secret: String) { secrets[username] = secret }
        override fun delete(username: String) { secrets.remove(username) }
    }

    private val hasher = BcryptPasswordHasher()
    private val tokens = Tokens()
    private val secrets = Secrets()
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setUp() {
        val repo = Memory().also { it.accounts["admin"] = Account("admin", hasher.hash("correct-horse")) }
        val clock = Clock.systemUTC()
        val auth = AuthService(repo, hasher, clock)
        val remember = RememberMeService(tokens, clock)
        val cookie = RememberCookie(false)
        mvc = MockMvcBuilders.standaloneSetup(AuthController(auth, TwoFactorService(secrets, clock), remember, cookie))
            .addFilters<org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder>(SessionFilter(remember, cookie)).build()
    }

    private fun rememberCookie(result: MvcResult): Cookie? =
        result.response.getHeaders("Set-Cookie").firstOrNull { it.startsWith("ILSIM_REMEMBER=") && !it.startsWith("ILSIM_REMEMBER=;") }
            ?.substringAfter("=")?.substringBefore(";")?.let { Cookie(RememberCookie.NAME, it) }

    private fun nowCode(secret: String) = Totp.code(secret, Totp.step(Instant.now()))

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

    @Test
    fun `자동 로그인을 고르면 세션 없이도 쿠키로 다시 들어오고 로그아웃하면 끊긴다`() {
        val plain = mvc.perform(json("/api/auth/login", """{"username":"admin","password":"correct-horse"}""")).andReturn()
        assertThat(rememberCookie(plain)).isNull()

        val result = mvc.perform(json("/api/auth/login", """{"username":"admin","password":"correct-horse","remember":true}""")).andReturn()
        val cookie = rememberCookie(result)!!
        assertThat(result.response.getHeader("Set-Cookie")).contains("HttpOnly").contains("SameSite=Lax")

        val restored = mvc.perform(get("/api/auth/me").cookie(cookie)).andExpect(jsonPath("$.user").value("admin")).andReturn()
        mvc.perform(get("/api/tasks").cookie(cookie)).andExpect(status().isNotFound) // 인증은 통과(컨트롤러가 없어 404)

        mvc.perform(post("/api/auth/logout").csrf().cookie(cookie).session(sessionOf(restored))).andExpect(status().isNoContent)
        mvc.perform(get("/api/auth/me").cookie(cookie)).andExpect(jsonPath("$.user").doesNotExist())
    }

    @Test
    fun `위조한 자동 로그인 쿠키는 통하지 않고 지워진다`() {
        val cookie = rememberCookie(mvc.perform(json("/api/auth/login", """{"username":"admin","password":"correct-horse","remember":true}""")).andReturn())!!
        val forged = Cookie(RememberCookie.NAME, cookie.value.substringBefore(":") + ":" + "x".repeat(43))
        mvc.perform(get("/api/tasks").cookie(forged)).andExpect(status().isUnauthorized)
        assertThat(tokens.tokens).isEmpty()
    }

    @Test
    fun `비밀번호를 바꾸면 모든 자동 로그인이 끊긴다`() {
        val cookie = rememberCookie(mvc.perform(json("/api/auth/login", """{"username":"admin","password":"correct-horse","remember":true}""")).andReturn())!!
        val session = login()
        mvc.perform(json("/api/auth/password", """{"currentPassword":"correct-horse","newPassword":"brand-new-pass"}""", session))
            .andExpect(status().isNoContent)
        mvc.perform(get("/api/auth/me").cookie(cookie)).andExpect(jsonPath("$.user").doesNotExist())
    }

    @Test
    fun `2단계 인증을 등록하면 다음 로그인부터 코드를 묻고 맞아야 세션이 생긴다`() {
        val session = login()
        val setup = mvc.perform(post("/api/auth/totp/setup").csrf().session(session)).andExpect(status().isOk).andReturn()
        val secret = Regex("\"secret\":\"([A-Z2-7]+)\"").find(setup.response.contentAsString)!!.groupValues[1]
        assertThat(setup.response.contentAsString).contains("otpauth://totp/ilsim:admin")
        mvc.perform(json("/api/auth/totp/enable", """{"code":"000000"}""", session)).andExpect(status().isForbidden)
        mvc.perform(json("/api/auth/totp/enable", """{"code":"${nowCode(secret)}"}""", session)).andExpect(status().isNoContent)
        mvc.perform(get("/api/auth/me").session(session)).andExpect(jsonPath("$.twoFactor").value(true))

        val first = mvc.perform(json("/api/auth/login", """{"username":"admin","password":"correct-horse","remember":true}"""))
            .andExpect(jsonPath("$.otpRequired").value(true)).andExpect(jsonPath("$.user").doesNotExist()).andReturn()
        val pending = sessionOf(first)
        assertThat(rememberCookie(first)).isNull()
        mvc.perform(get("/api/tasks").session(pending)).andExpect(status().isUnauthorized)
        mvc.perform(get("/api/auth/me").session(pending)).andExpect(jsonPath("$.otpRequired").value(true))

        // 등록할 때 쓴 코드는 다시 쓸 수 없어 다음 칸 코드로 확인한다.
        val next = Totp.code(secret, Totp.step(Instant.now()) + 1)
        mvc.perform(json("/api/auth/otp", """{"code":"123"}""", pending)).andExpect(status().isUnauthorized)
        val done = mvc.perform(json("/api/auth/otp", """{"code":"$next"}""", pending))
            .andExpect(status().isOk).andExpect(jsonPath("$.user").value("admin")).andReturn()
        assertThat(rememberCookie(done)).isNotNull()
        mvc.perform(json("/api/auth/otp", """{"code":"$next"}""", sessionOf(done))).andExpect(status().isUnauthorized)
    }

    @Test
    fun `비밀번호 없이 코드 단계로 바로 갈 수 없고 코드 실패가 쌓이면 잠긴다`() {
        secrets.secrets["admin"] = Totp.newSecret()
        mvc.perform(json("/api/auth/otp", """{"code":"123456"}""")).andExpect(status().isUnauthorized).andExpect(jsonPath("$.error").value("다시 로그인하세요"))

        val pending = sessionOf(mvc.perform(json("/api/auth/login", """{"username":"admin","password":"correct-horse"}""")).andReturn())
        repeat(5) { mvc.perform(json("/api/auth/otp", """{"code":"000000"}""", pending)) }
        mvc.perform(json("/api/auth/otp", """{"code":"${nowCode(secrets.secrets["admin"]!!)}"}""", pending)).andExpect(status().isTooManyRequests)
    }

    @Test
    fun `TOTP 는 RFC 6238 시험 값과 같다`() {
        val secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ" // "12345678901234567890"
        assertThat(Totp.code(secret, Totp.step(Instant.ofEpochSecond(59)))).isEqualTo("287082")
        assertThat(Totp.code(secret, Totp.step(Instant.ofEpochSecond(1111111109)))).isEqualTo("081804")
        assertThat(Totp.verify(secret, "081804", Instant.ofEpochSecond(1111111109 + 30))).isNotNull()
        assertThat(Totp.verify(secret, "081804", Instant.ofEpochSecond(1111111109 + 90))).isNull()
    }
}
