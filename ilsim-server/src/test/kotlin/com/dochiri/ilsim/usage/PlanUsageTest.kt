package com.dochiri.ilsim.usage

import com.dochiri.ilsim.usage.adapter.`in`.web.UsageController
import com.dochiri.ilsim.usage.adapter.out.memory.InMemoryPlanUsageStore
import com.dochiri.ilsim.usage.application.usecase.PlanUsageService
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/** Runner 가 보고한 플랜 사용량이 웹에 보이고, 웹의 새로고침 요청이 Runner 에 한 번만 넘어가는지 본다. */
class PlanUsageTest {

    private val now = Instant.parse("2026-10-17T00:00:00Z")
    private lateinit var service: PlanUsageService
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setUp() {
        service = PlanUsageService(InMemoryPlanUsageStore(), Clock.fixed(now, ZoneOffset.UTC))
        mvc = MockMvcBuilders.standaloneSetup(UsageController(service)).build()
    }

    @Test
    fun `Runner 가 보고한 사용량을 웹에서 본다`() {
        mvc.perform(
            post("/runner/api/usage").contentType(MediaType.APPLICATION_JSON).content(
                """{"runnerId":"pc","status":"allowed","fiveHour":{"utilization":0.22,"resetsAt":1792257600},"sevenDay":{"utilization":0.34,"resetsAt":1792666800}}""",
            ),
        ).andExpect(status().isOk)

        mvc.perform(get("/api/usage"))
            .andExpect(jsonPath("$[0].runnerId").value("pc"))
            .andExpect(jsonPath("$[0].fiveHour.utilization").value(0.22))
            .andExpect(jsonPath("$[0].sevenDay.utilization").value(0.34))
        assertThat(service.all().single().fiveHour?.resetsAt).isEqualTo(Instant.ofEpochSecond(1792257600))
        assertThat(service.all().single().observedAt).isEqualTo(now)
    }

    @Test
    fun `새로고침 요청은 해당 Runner 가 한 번만 가져간다`() {
        assertThat(service.claimRefresh("pc")).isFalse()

        mvc.perform(post("/api/usage/pc/refresh")).andExpect(status().isAccepted)

        assertThat(service.claimRefresh("other")).isFalse()
        mvc.perform(post("/runner/api/usage/claim").contentType(MediaType.APPLICATION_JSON).content("""{"runnerId":"pc"}"""))
            .andExpect(status().isOk)
        mvc.perform(post("/runner/api/usage/claim").contentType(MediaType.APPLICATION_JSON).content("""{"runnerId":"pc"}"""))
            .andExpect(status().isNoContent)
    }

    @Test
    fun `빈 Runner id 는 거절한다`() {
        mvc.perform(post("/runner/api/usage").contentType(MediaType.APPLICATION_JSON).content("""{"runnerId":" "}"""))
            .andExpect(status().isBadRequest)
    }
}
