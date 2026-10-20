package com.dochiri.ilsim.claude

import com.dochiri.ilsim.claude.adapter.`in`.web.ClaudeController
import com.dochiri.ilsim.claude.adapter.out.memory.InMemoryClaudeAccountStore
import com.dochiri.ilsim.claude.application.port.out.ClaudeEnvironmentStore
import com.dochiri.ilsim.claude.application.usecase.ClaudeService
import com.dochiri.ilsim.claude.domain.ClaudeEnvironment
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class ClaudeAccountTest {

    private class MemoryEnvironment : ClaudeEnvironmentStore {
        var saved: ClaudeEnvironment? = null
        override fun load() = saved
        override fun save(environment: ClaudeEnvironment) { saved = environment }
    }

    private val now = Instant.parse("2026-10-18T00:00:00Z")
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setUp() {
        val service = ClaudeService(MemoryEnvironment(), MemorySkills(), InMemoryClaudeAccountStore(), { it.trim().startsWith("{") && it.trim().endsWith("}") }, Clock.fixed(now, ZoneOffset.UTC))
        mvc = MockMvcBuilders.standaloneSetup(ClaudeController(service)).build()
    }

    private fun json(path: String, body: String) = post(path).contentType(MediaType.APPLICATION_JSON).content(body)

    @Test
    fun `웹 로그인 요청을 Runner 가 가져가 URL 을 올리고 사용자가 넣은 코드를 한 번만 받는다`() {
        val id = mvc.perform(json("/api/claude/logins", """{"runnerId":"pc","name":"Work"}""")).andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("work")).andReturn().response.contentAsString.let { Regex("\"id\":\"([^\"]+)\"").find(it)!!.groupValues[1] }
        mvc.perform(json("/api/claude/logins", """{"runnerId":"pc","name":"work"}""")).andExpect(status().isBadRequest)
        mvc.perform(json("/runner/api/claude/logins/claim", """{"runnerId":"other"}""")).andExpect(status().isNoContent)

        mvc.perform(json("/runner/api/claude/logins/claim", """{"runnerId":"pc"}""")).andExpect(jsonPath("$.id").value(id))
        mvc.perform(json("/api/claude/logins/$id/code", """{"code":"abc"}""")).andExpect(status().isBadRequest)
        mvc.perform(json("/runner/api/claude/logins/$id/url", """{"url":"https://claude.com/cai/oauth/authorize?x=1"}"""))
            .andExpect(jsonPath("$.status").value("WAITING_CODE"))
        mvc.perform(json("/runner/api/claude/logins/$id/code", "{}")).andExpect(status().isNoContent)

        mvc.perform(json("/api/claude/logins/$id/code", """{"code":"abc#def"}""")).andExpect(jsonPath("$.status").value("CODE_SUBMITTED"))
            .andExpect(jsonPath("$.code").doesNotExist())
        mvc.perform(json("/runner/api/claude/logins/$id/code", "{}")).andExpect(jsonPath("$.code").value("abc#def"))
        mvc.perform(json("/runner/api/claude/logins/$id/code", "{}")).andExpect(status().isNoContent)

        mvc.perform(json("/runner/api/claude/logins/$id/result", """{"loggedIn":true,"email":"w@x.com","plan":"pro"}""")).andExpect(jsonPath("$.status").value("DONE"))
        mvc.perform(json("/runner/api/claude/accounts", """{"runnerId":"pc","accounts":[{"name":"work","loggedIn":true,"email":"w@x.com","plan":"pro"},{"name":"free","loggedIn":true}]}"""))
            .andExpect(content().json("[]"))
        mvc.perform(get("/api/claude/accounts")).andExpect(jsonPath("$.accounts[1].name").value("work"))
            .andExpect(jsonPath("$.accounts[1].usable").value(true)).andExpect(jsonPath("$.accounts[0].usable").value(false))
            .andExpect(jsonPath("$.logins[0].status").value("DONE"))
    }

    @Test
    fun `지운 계정은 목록에서 빠지고 Runner 가 다음 보고 때 정리 일감으로 받는다`() {
        mvc.perform(json("/runner/api/claude/accounts", """{"runnerId":"pc","accounts":[{"name":"work","loggedIn":true,"plan":"pro"}]}"""))
        mvc.perform(delete("/api/claude/accounts/pc/work")).andExpect(status().isNoContent)
        mvc.perform(get("/api/claude/accounts")).andExpect(jsonPath("$.accounts").isEmpty)

        mvc.perform(json("/runner/api/claude/accounts", """{"runnerId":"pc","accounts":[{"name":"work","loggedIn":true,"plan":"pro"}]}"""))
            .andExpect(content().json("""["work"]"""))
        mvc.perform(json("/runner/api/claude/accounts", """{"runnerId":"pc","accounts":[]}""")).andExpect(content().json("[]"))
    }

    @Test
    fun `공통 설정은 JSON 객체만 받고 내용이 바뀌면 버전이 바뀐다`() {
        mvc.perform(put("/api/claude/environment").contentType(MediaType.APPLICATION_JSON).content("""{"claudeMd":"# 규칙","settingsJson":"[1]"}"""))
            .andExpect(status().isBadRequest)
        val first = mvc.perform(put("/api/claude/environment").contentType(MediaType.APPLICATION_JSON).content("""{"claudeMd":"# 규칙","settingsJson":"{\"model\":\"opus\"}"}"""))
            .andExpect(jsonPath("$.claudeMd").value("# 규칙")).andReturn().response.contentAsString
        mvc.perform(get("/runner/api/claude/environment")).andExpect(jsonPath("$.settingsJson").value("""{"model":"opus"}"""))
        val second = mvc.perform(put("/api/claude/environment").contentType(MediaType.APPLICATION_JSON).content("""{"claudeMd":"# 바뀐 규칙"}"""))
            .andExpect(jsonPath("$.settingsJson").value("{}")).andReturn().response.contentAsString
        assertThat(Regex("\"version\":\"(\\w+)\"").find(first)!!.groupValues[1]).isNotEqualTo(Regex("\"version\":\"(\\w+)\"").find(second)!!.groupValues[1])
    }
}
