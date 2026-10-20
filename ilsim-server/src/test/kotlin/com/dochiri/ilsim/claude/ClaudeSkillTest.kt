package com.dochiri.ilsim.claude

import com.dochiri.ilsim.claude.adapter.`in`.web.ClaudeController
import com.dochiri.ilsim.claude.adapter.out.memory.InMemoryClaudeAccountStore
import com.dochiri.ilsim.claude.application.port.out.ClaudeEnvironmentStore
import com.dochiri.ilsim.claude.application.usecase.ClaudeService
import com.dochiri.ilsim.claude.domain.ClaudeEnvironment
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class ClaudeSkillTest {

    private class MemoryEnvironment : ClaudeEnvironmentStore {
        var saved: ClaudeEnvironment? = null
        override fun load() = saved
        override fun save(environment: ClaudeEnvironment) { saved = environment }
    }

    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setUp() {
        val service = ClaudeService(MemoryEnvironment(), MemorySkills(), InMemoryClaudeAccountStore(), { it.trim().startsWith("{") },
            Clock.fixed(Instant.parse("2026-10-18T00:00:00Z"), ZoneOffset.UTC))
        mvc = MockMvcBuilders.standaloneSetup(ClaudeController(service)).build()
    }

    private fun putSkill(name: String, body: String) =
        mvc.perform(put("/api/claude/skills/$name").contentType(MediaType.APPLICATION_JSON).content(body))

    private val skillMd = "---\\nname: review\\ndescription: 변경을 검토한다\\n---\\n스크립트는 이 스킬 폴더의 scripts/check.sh 를 쓴다"

    @Test
    fun `스킬을 등록하면 Runner 가 받는 공통 설정에 실리고 버전이 바뀐다`() {
        val before = mvc.perform(get("/runner/api/claude/environment")).andReturn().response.contentAsString
        putSkill("review", """{"files":{"SKILL.md":"$skillMd","scripts/check.sh":"echo ok"}}""")
            .andExpect(status().isOk).andExpect(jsonPath("$.name").value("review"))

        mvc.perform(get("/runner/api/claude/environment"))
            .andExpect(jsonPath("$.skills[0].name").value("review"))
            .andExpect(jsonPath("$.skills[0].files['scripts/check.sh']").value("echo ok"))
            .andExpect { assert(!before.contains(it.response.contentAsString.substringAfter("\"version\":\"").take(16))) }

        mvc.perform(delete("/api/claude/skills/review")).andExpect(status().isNoContent)
        mvc.perform(get("/api/claude/skills")).andExpect(jsonPath("$.length()").value(0))
    }

    @Test
    fun `PC마다 다른 절대 경로나 폴더 밖 경로가 있으면 거절한다`() {
        putSkill("review", """{"files":{"SKILL.md":"---\ndescription: x\n---\n/Users/someone/tools/run.sh 실행"}}""")
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("/Users/someone")))
        putSkill("review", """{"files":{"SKILL.md":"---\ndescription: x\n---\n~/tools/run.sh","../escape.sh":"x"}}""")
            .andExpect(status().isBadRequest)
        putSkill("Bad_Name", """{"files":{"SKILL.md":"---\ndescription: x\n---\n"}}""").andExpect(status().isBadRequest)
        putSkill("review", """{"files":{"SKILL.md":"설명 없음"}}""").andExpect(status().isBadRequest)
        putSkill("review", """{"files":{"SKILL.md":"---\ndescription: x\n---\n~/tools/run.sh 와 ${'$'}HOME/bin"}}""").andExpect(status().isOk)

        mvc.perform(put("/api/claude/environment").contentType(MediaType.APPLICATION_JSON)
            .content("""{"claudeMd":"C:\\Users\\kim\\notes","settingsJson":"{}"}"""))
            .andExpect(status().isBadRequest)
    }
}
