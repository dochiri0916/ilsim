package com.dochiri.ilsim.task

import com.dochiri.ilsim.task.adapter.`in`.web.TaskController
import com.dochiri.ilsim.task.adapter.`in`.web.TaskExceptionHandler
import com.dochiri.ilsim.task.adapter.out.persistence.TaskRecord
import com.dochiri.ilsim.task.application.command.ReportStageCommand
import com.dochiri.ilsim.task.application.usecase.ModelSelector
import com.dochiri.ilsim.task.application.usecase.TaskService
import com.dochiri.ilsim.task.domain.ModelTier
import com.dochiri.ilsim.task.domain.Project
import com.dochiri.ilsim.task.domain.Task
import com.dochiri.ilsim.task.domain.exception.InvalidTaskRequestException
import com.dochiri.ilsim.task.domain.exception.InvalidTaskStateException
import com.dochiri.ilsim.task.domain.vo.Stage
import com.dochiri.ilsim.task.domain.vo.StageStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

/** 요구사항 단계의 대화: 같은 세션을 이어 문서를 고치고, 승인하면 세션을 닫는다. */
class RequirementsConversationTest {

    private val demo = Project("demo", "demo", null)
    private lateinit var service: TaskService
    private lateinit var task: Task

    @BeforeEach
    fun setUp() {
        service = TaskService(ModelSelector(NoAssessor, ModelTier.SONNET), NoStore, MutableClock(), "medium")
        service.poll("r1", listOf(demo))
        task = service.create("demo", "slug", "slugify 추가")
    }

    private fun poll() = service.poll("r1", listOf(demo))

    private fun pass(session: String? = "s1") =
        ReportStageCommand(passed = true, retryable = false, diff = "d", branch = "b", sessionId = session)

    private fun requirements() = service.latest(task).stage(Stage.REQUIREMENTS)

    private fun waiting() {
        poll(); service.report(task.id.value, pass())
    }

    @Test
    fun `첫 실행의 세션을 기억하고 메시지를 보내면 같은 세션을 이어 문서를 고치게 배정한다`() {
        waiting()
        assertThat(requirements().sessionId).isEqualTo("s1")
        assertThat(requirements().status).isEqualTo(StageStatus.WAITING_APPROVAL)

        service.message(task.id.value, "  미결정 1번은 A 로 해줘  ")
        assertThat(requirements().status).isEqualTo(StageStatus.PENDING)
        assertThat(requirements().messages).containsExactly("미결정 1번은 A 로 해줘")
        assertThat(service.events(task.id.value).last())
            .extracting({ it.stage }, { it.type }, { it.data })
            .containsExactly("REQUIREMENTS", "message", "미결정 1번은 A 로 해줘")

        val a = poll()!!
        assertThat(a.stage).isEqualTo("REQUIREMENTS")
        assertThat(a.resumeSessionId).isEqualTo("s1")
        assertThat(a.prompt).contains("미결정 1번은 A 로 해줘").contains("specs/slug/requirements.md")

        service.report(task.id.value, pass("s1"))
        assertThat(requirements().status).isEqualTo(StageStatus.WAITING_APPROVAL)
        assertThat(requirements().pendingMessage).isNull()
    }

    @Test
    fun `첫 배정은 새 세션이고 승인하면 세션을 닫지만 대화 기록은 남는다`() {
        val first = poll()!!
        assertThat(first.resumeSessionId).isNull()
        service.report(task.id.value, pass())
        service.message(task.id.value, "수정")
        poll(); service.report(task.id.value, pass())
        service.approve(task.id.value)
        assertThat(requirements().sessionId).isNull()
        assertThat(requirements().messages).containsExactly("수정")
        assertThat(poll()!!.resumeSessionId).isNull() // 다음 단계는 항상 새 세션
    }

    @Test
    fun `대화한 실행이 실패해 재시도돼도 세션을 이어 가고 거절하면 새로 시작한다`() {
        waiting()
        service.message(task.id.value, "추가해 줘")
        poll()
        service.report(task.id.value, ReportStageCommand(passed = false, retryable = true, error = "범위 위반", sessionId = "s1"))
        val retry = poll()!!
        assertThat(retry.resumeSessionId).isEqualTo("s1")
        assertThat(retry.prompt).contains("추가해 줘").contains("범위 위반")

        service.report(task.id.value, pass())
        service.reject(task.id.value, "처음부터 다시")
        val fresh = poll()!!
        assertThat(fresh.resumeSessionId).isNull()
        assertThat(fresh.prompt).contains("처음부터 다시")
    }

    @Test
    fun `승인 대기가 아니거나 빈 메시지거나 요구사항 단계가 아니면 거절한다`() {
        assertThatThrownBy { service.message(task.id.value, "지금은 대기 아님") }.isInstanceOf(InvalidTaskStateException::class.java)
        waiting()
        assertThatThrownBy { service.message(task.id.value, "  ") }.isInstanceOf(InvalidTaskRequestException::class.java)
        service.approve(task.id.value)
        poll(); service.report(task.id.value, pass()) // TEST_CASES 가 승인 대기
        assertThatThrownBy { service.message(task.id.value, "테스트 케이스에 말 걸기") }
            .isInstanceOf(InvalidTaskStateException::class.java).hasMessageContaining("요구사항")
    }

    @Test
    fun `대화는 최대 횟수까지만 할 수 있다`() {
        waiting()
        repeat(Task.MAX_MESSAGES) { i ->
            service.message(task.id.value, "메시지 $i")
            poll(); service.report(task.id.value, pass())
        }
        assertThatThrownBy { service.message(task.id.value, "하나 더") }.isInstanceOf(InvalidTaskStateException::class.java)
    }

    @Test
    fun `세션과 대화 기록은 저장했다 읽어도 유지된다`() {
        waiting()
        service.message(task.id.value, "수정해 줘")
        val json = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
        val restored = json.readValue(json.writeValueAsString(TaskRecord.from(service.latest(task))), TaskRecord::class.java).toTask()
        val stage = restored.stage(Stage.REQUIREMENTS)
        assertThat(stage.sessionId).isEqualTo("s1")
        assertThat(stage.messages).containsExactly("수정해 줘")
        assertThat(stage.pendingMessage).isEqualTo("수정해 줘")
    }

    @Test
    fun `HTTP 로 메시지를 보내면 200, 잘못된 요청은 400·404 이다`() {
        waiting()
        val mvc: MockMvc = MockMvcBuilders.standaloneSetup(TaskController(service, service)).setControllerAdvice(TaskExceptionHandler()).build()
        fun send(body: String) = mvc.perform(post("/api/tasks/${task.id.value}/messages").contentType(MediaType.APPLICATION_JSON).content(body))
        send("""{"text":"반영해 줘"}""").andExpect(status().isOk)
        send("""{"text":""}""").andExpect(status().isBadRequest)
        mvc.perform(post("/api/tasks/none/messages").contentType(MediaType.APPLICATION_JSON).content("""{"text":"x"}""")).andExpect(status().isNotFound)
    }
}
