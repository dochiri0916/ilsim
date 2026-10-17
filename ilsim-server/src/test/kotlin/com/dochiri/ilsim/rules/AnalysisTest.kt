package com.dochiri.ilsim.rules

import com.dochiri.ilsim.rules.adapter.`in`.web.AnalysisController
import com.dochiri.ilsim.rules.adapter.out.memory.InMemoryAnalysisStore
import com.dochiri.ilsim.rules.application.usecase.AnalysisService
import com.dochiri.ilsim.rules.domain.AnalysisStatus
import com.dochiri.ilsim.rules.domain.exception.AnalysisInProgressException
import com.dochiri.ilsim.rules.domain.exception.InvalidRuleRequestException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/** 웹 분석 요청이 Runner 에 한 번만 넘어가고, 결과가 다시 웹에 보이는지 본다. */
class AnalysisTest {

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant() = now
    }

    private val clock = MutableClock(Instant.parse("2026-10-17T00:00:00Z"))
    private lateinit var service: AnalysisService
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setUp() {
        service = AnalysisService(InMemoryAnalysisStore(), clock)
        mvc = MockMvcBuilders.standaloneSetup(AnalysisController(service)).build()
    }

    @Test
    fun `요청한 분석은 해당 프로젝트를 가진 Runner 가 한 번만 가져간다`() {
        service.request("osiv")

        assertThat(service.claim(listOf("sandbox"))).isNull()
        assertThat(service.claim(listOf("sandbox", "osiv"))?.status).isEqualTo(AnalysisStatus.RUNNING)
        assertThat(service.claim(listOf("osiv"))).isNull()
    }

    @Test
    fun `결과를 보고하면 완료·실패 상태와 보고서가 남는다`() {
        service.request("osiv")
        service.claim(listOf("osiv"))

        val done = service.complete("osiv", passed = false, report = "[아키텍처] ...", error = "컨벤션 분석 실패")

        assertThat(done.status).isEqualTo(AnalysisStatus.FAILED)
        assertThat(service.latest("osiv")?.report).isEqualTo("[아키텍처] ...")
        assertThat(service.latest("osiv")?.error).isEqualTo("컨벤션 분석 실패")
    }

    @Test
    fun `진행 중에는 다시 요청할 수 없고 끝나거나 오래 멈추면 다시 요청할 수 있다`() {
        service.request("osiv")
        assertThatThrownBy { service.request("osiv") }.isInstanceOf(AnalysisInProgressException::class.java)

        service.claim(listOf("osiv"))
        assertThatThrownBy { service.request("osiv") }.isInstanceOf(AnalysisInProgressException::class.java)

        clock.now = clock.now.plus(Duration.ofMinutes(31))
        assertThat(service.request("osiv").status).isEqualTo(AnalysisStatus.REQUESTED)

        service.claim(listOf("osiv"))
        service.complete("osiv", passed = true, report = "ok", error = null)
        assertThat(service.request("osiv").status).isEqualTo(AnalysisStatus.REQUESTED)
    }

    @Test
    fun `요청하지 않았거나 실행 중이 아닌 분석의 결과는 거절한다`() {
        assertThatThrownBy { service.complete("osiv", true, "ok", null) }.isInstanceOf(InvalidRuleRequestException::class.java)

        service.request("osiv")
        assertThatThrownBy { service.complete("osiv", true, "ok", null) }.isInstanceOf(InvalidRuleRequestException::class.java)
    }

    @Test
    fun `HTTP 로 요청·가져가기·결과 보고를 한다`() {
        mvc.perform(get("/api/projects/osiv/knowledge/analysis")).andExpect(status().isNoContent)
        mvc.perform(post("/api/projects/osiv/knowledge/analysis"))
            .andExpect(status().isAccepted).andExpect(jsonPath("$.status").value("REQUESTED"))
        mvc.perform(post("/api/projects/osiv/knowledge/analysis")).andExpect(status().isConflict)

        mvc.perform(post("/runner/api/analysis/claim").contentType(MediaType.APPLICATION_JSON).content("""{"runnerId":"r","projects":["sandbox"]}"""))
            .andExpect(status().isNoContent)
        mvc.perform(post("/runner/api/analysis/claim").contentType(MediaType.APPLICATION_JSON).content("""{"runnerId":"r","projects":["osiv"]}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.projectId").value("osiv"))

        mvc.perform(post("/runner/api/analysis/osiv/result").contentType(MediaType.APPLICATION_JSON).content("""{"passed":true,"report":"규칙 20개"}"""))
            .andExpect(status().isOk)
        mvc.perform(get("/api/projects/osiv/knowledge/analysis"))
            .andExpect(jsonPath("$.status").value("DONE")).andExpect(jsonPath("$.report").value("규칙 20개"))
        mvc.perform(post("/runner/api/analysis/osiv/result").contentType(MediaType.APPLICATION_JSON).content("""{"passed":true}"""))
            .andExpect(status().isBadRequest)
    }
}
