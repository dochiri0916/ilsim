package com.dochiri.ilsim.task

import com.dochiri.ilsim.task.adapter.`in`.web.RunnerController
import com.dochiri.ilsim.task.adapter.`in`.web.TaskController
import com.dochiri.ilsim.task.adapter.`in`.web.TaskExceptionHandler
import com.dochiri.ilsim.task.application.usecase.ModelSelector
import com.dochiri.ilsim.task.application.usecase.TaskService
import com.dochiri.ilsim.task.domain.ModelTier
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/** 잘못된 요청이 서버 오류(500)가 아니라 400·404 로 응답되는지 본다. */
class TaskHttpFailureTest {

    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setUp() {
        val service = TaskService(ModelSelector(NoAssessor, ModelTier.SONNET), NoStore, MutableClock(), "medium")
        mvc = MockMvcBuilders.standaloneSetup(TaskController(service, service), RunnerController(service))
            .setControllerAdvice(TaskExceptionHandler())
            .build()
    }

    private fun json(url: String, body: String) = post(url).contentType(MediaType.APPLICATION_JSON).content(body)

    @Test
    fun `등록되지 않은 프로젝트나 빈 요구사항은 400 이다`() {
        mvc.perform(json("/api/tasks", """{"projectId":"none","requirement":"x"}"""))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.error").exists())
        mvc.perform(json("/api/tasks", """{"projectId":"none","requirement":""}"""))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `없는 작업은 조회 404, 승인 거절 재시도도 404 이다`() {
        mvc.perform(get("/api/tasks/none")).andExpect(status().isNotFound)
        mvc.perform(post("/api/tasks/none/approve")).andExpect(status().isNotFound)
        mvc.perform(json("/api/tasks/none/reject", """{"feedback":"x"}""")).andExpect(status().isNotFound)
        mvc.perform(post("/api/tasks/none/retry")).andExpect(status().isNotFound)
        mvc.perform(json("/runner/api/tasks/none/result", """{"passed":true,"retryable":false}""")).andExpect(status().isNotFound)
    }

    @Test
    fun `깨진 JSON 은 서버 오류가 아니라 400 이다`() {
        mvc.perform(json("/api/tasks", "{깨진")).andExpect(status().isBadRequest)
    }
}
