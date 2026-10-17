package com.dochiri.ilsim.rules

import com.dochiri.ilsim.rules.adapter.`in`.web.KnowledgeController
import com.dochiri.ilsim.rules.adapter.out.file.FileKnowledgeStore
import com.dochiri.ilsim.rules.application.usecase.KnowledgeService
import com.dochiri.ilsim.rules.domain.Decision
import com.dochiri.ilsim.rules.domain.RuleSet
import com.dochiri.ilsim.rules.domain.exception.InvalidRuleRequestException
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/** Runner 가 쓴 형식의 파일을 서버가 읽고, 승인 상태만 바꾸는지 본다. */
class KnowledgeTest {

    @TempDir
    lateinit var home: Path

    private lateinit var service: KnowledgeService
    private lateinit var mvc: MockMvc

    private val architecture = """
        {"projectName":"demo","rules":[
          {"id":"A1","kind":"LAYER","description":"도메인은 어댑터를 모른다","params":{"from":["domain"]},"proposedSeverity":"BLOCK",
           "evidence":{"matches":10,"violations":0},"approved":false,"rejected":false,"review":{"verdict":"KEEP","reason":"근거 충분"}},
          {"id":"A2","kind":"NAMING","description":"서비스 이름","params":{},"proposedSeverity":"REVIEW",
           "evidence":{"matches":3,"violations":1},"approved":true,"rejected":false,"decidedBy":"ai-review"}
        ],"extra":{"keep":"me"}}"""

    private val memory = """
        [{"id":"M1","kind":"FALSE_POSITIVE","ruleId":"R","file":"a/B.java","fingerprint":"f1","summary":"오탐","source":"ai","status":"CANDIDATE","createdAt":"2026-01-01T00:00:00Z"},
         {"id":"M2","kind":"CONFIRMED_RISK","ruleId":"R","file":"a/C.java","fingerprint":"f2","summary":"실제 위험","source":"ai","status":"SUPERSEDED","createdAt":"2026-01-01T00:00:00Z","supersededReason":"근거 소멸"}]"""

    private fun dir(): Path = home.resolve("projects/demo").also { Files.createDirectories(it) }

    @BeforeEach
    fun setUp() {
        Files.writeString(dir().resolve("architecture.json"), architecture)
        Files.writeString(dir().resolve("memory.json"), memory)
        service = KnowledgeService(FileKnowledgeStore(home))
        mvc = MockMvcBuilders.standaloneSetup(KnowledgeController(service)).build()
    }

    @Test
    fun `규칙과 메모리를 읽고 파일이 없는 종류는 빈 목록이다`() {
        val k = service.overview("demo")
        assertThat(k.architecture.map { it.id }).containsExactly("A1", "A2")
        assertThat(k.architecture[0].reviewVerdict).isEqualTo("KEEP")
        assertThat(k.architecture[0].severity).isEqualTo("BLOCK")
        assertThat(k.architecture[1].decidedBy).isEqualTo("ai-review")
        assertThat(k.convention).isEmpty()
        assertThat(k.memory.map { it.status }).containsExactly("CANDIDATE", "SUPERSEDED")
        assertThat(service.overview("other").architecture).isEmpty()
    }

    @Test
    fun `승인하면 사용자 결정으로 바뀌고 다른 규칙과 모르는 필드는 그대로다`() {
        service.decideRules("demo", RuleSet.ARCHITECTURE, listOf("A1"), Decision.APPROVE)
        val a1 = service.overview("demo").architecture.first { it.id == "A1" }
        assertThat(a1.approved).isTrue()
        assertThat(a1.rejected).isFalse()
        assertThat(a1.decidedBy).isEqualTo("user")
        val raw = Files.readString(dir().resolve("architecture.json"))
        assertThat(raw).contains("\"extra\"").contains("\"violations\"").contains("근거 충분")
        assertThat(service.overview("demo").architecture.first { it.id == "A2" }.decidedBy).isEqualTo("ai-review")
    }

    @Test
    fun `거절하면 승인이 풀리고 거절로 표시된다`() {
        service.decideRules("demo", RuleSet.ARCHITECTURE, listOf("A2"), Decision.REJECT)
        val a2 = service.overview("demo").architecture.first { it.id == "A2" }
        assertThat(a2.approved).isFalse()
        assertThat(a2.rejected).isTrue()
        assertThat(a2.decidedBy).isEqualTo("user")
    }

    @Test
    fun `모르는 id 가 섞이면 아무것도 바꾸지 않는다`() {
        val before = Files.readString(dir().resolve("architecture.json"))
        assertThatThrownBy { service.decideRules("demo", RuleSet.ARCHITECTURE, listOf("A1", "NOPE"), Decision.APPROVE) }
            .isInstanceOf(InvalidRuleRequestException::class.java).hasMessageContaining("NOPE")
        assertThat(Files.readString(dir().resolve("architecture.json"))).isEqualTo(before)
        assertThatThrownBy { service.decideRules("demo", RuleSet.ARCHITECTURE, emptyList(), Decision.APPROVE) }
            .isInstanceOf(InvalidRuleRequestException::class.java)
    }

    @Test
    fun `메모리는 승인하면 ACTIVE, 거절하면 SUPERSEDED 이고 이미 폐기된 항목은 그대로다`() {
        service.decideMemory("demo", listOf("M1", "M2"), Decision.APPROVE)
        val approved = service.overview("demo").memory.associateBy { it.id }
        assertThat(approved.getValue("M1").status).isEqualTo("ACTIVE")
        assertThat(approved.getValue("M1").approvedAt).isNotNull()
        assertThat(approved.getValue("M2").status).isEqualTo("SUPERSEDED")

        service.decideMemory("demo", listOf("M1"), Decision.REJECT)
        val rejected = service.overview("demo").memory.first { it.id == "M1" }
        assertThat(rejected.status).isEqualTo("SUPERSEDED")
        assertThat(rejected.supersededReason).isEqualTo("사용자가 거절")
    }

    @Test
    fun `경로를 벗어나는 프로젝트 id 는 거절한다`() {
        assertThatThrownBy { service.overview("../etc") }.isInstanceOf(InvalidRuleRequestException::class.java)
    }

    @Test
    fun `HTTP 로 조회하고 승인하며 잘못된 요청은 400 이다`() {
        mvc.perform(get("/api/projects/demo/knowledge"))
            .andExpect(status().isOk).andExpect(jsonPath("$.architecture[0].id").value("A1")).andExpect(jsonPath("$.memory[0].id").value("M1"))
        mvc.perform(post("/api/projects/demo/knowledge/rules/architecture/approve").contentType(MediaType.APPLICATION_JSON).content("""{"ids":["A1"]}"""))
            .andExpect(status().isNoContent)
        mvc.perform(post("/api/projects/demo/knowledge/rules/nothing/approve").contentType(MediaType.APPLICATION_JSON).content("""{"ids":["A1"]}"""))
            .andExpect(status().isBadRequest)
        mvc.perform(post("/api/projects/demo/knowledge/rules/architecture/reject").contentType(MediaType.APPLICATION_JSON).content("""{"ids":["NOPE"]}"""))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.error").exists())
        mvc.perform(post("/api/projects/demo/knowledge/memory/approve").contentType(MediaType.APPLICATION_JSON).content("""{}"""))
            .andExpect(status().isBadRequest)
    }
}
