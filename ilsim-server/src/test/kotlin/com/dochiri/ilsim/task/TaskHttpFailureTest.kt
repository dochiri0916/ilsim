package com.dochiri.ilsim.task

import com.dochiri.ilsim.task.adapter.`in`.web.RunnerController
import com.dochiri.ilsim.task.adapter.`in`.web.TaskExceptionHandler
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

class TaskHttpFailureTest {

    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setUp() {
        val service = TaskService(NoStore, MutableClock())
        mvc = MockMvcBuilders.standaloneSetup(RunnerController(service))
            .setControllerAdvice(TaskExceptionHandler())
            .build()
    }

    private fun json(url: String, body: String) = post(url).contentType(MediaType.APPLICATION_JSON).content(body)

    @Test
    fun `등록되지 않은 프로젝트나 빈 요구사항은 400 이다`() {
        mvc.perform(json("/runner/api/tasks", """{"projectId":"none","requirement":"x"}"""))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.error").exists())
        mvc.perform(json("/runner/api/tasks", """{"projectId":"none","requirement":""}"""))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `없는 작업은 조회 404, 승인 거절 재시도도 404 이다`() {
        mvc.perform(get("/runner/api/tasks/none")).andExpect(status().isNotFound)
        mvc.perform(post("/runner/api/tasks/none/approve")).andExpect(status().isNotFound)
        mvc.perform(json("/runner/api/tasks/none/reject", """{"feedback":"x"}""")).andExpect(status().isNotFound)
        mvc.perform(post("/runner/api/tasks/none/retry")).andExpect(status().isNotFound)
        mvc.perform(json("/runner/api/tasks/none/result", """{"passed":true,"retryable":false}""")).andExpect(status().isNotFound)
    }

    @Test
    fun `깨진 JSON 은 서버 오류가 아니라 400 이다`() {
        mvc.perform(json("/runner/api/tasks", "{깨진")).andExpect(status().isBadRequest)
    }
}
