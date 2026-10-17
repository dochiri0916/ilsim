package com.dochiri.ilsim.task

import com.dochiri.ilsim.task.application.command.ReportStageCommand
import com.dochiri.ilsim.task.application.port.out.TaskStore
import com.dochiri.ilsim.task.application.usecase.ModelSelector
import com.dochiri.ilsim.task.application.usecase.TaskService
import com.dochiri.ilsim.task.domain.ModelTier
import com.dochiri.ilsim.task.domain.Project
import com.dochiri.ilsim.task.domain.vo.Stage
import com.dochiri.ilsim.task.domain.vo.StageStatus
import com.dochiri.ilsim.task.domain.Task
import com.dochiri.ilsim.task.domain.vo.TaskStatus
import com.dochiri.ilsim.task.domain.exception.InvalidTaskRequestException
import com.dochiri.ilsim.task.domain.exception.InvalidTaskStateException
import com.dochiri.ilsim.task.domain.exception.TaskNotFoundException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** 정상 흐름이 아닌 요청(잘못된 순서, 없는 작업, 중복 보고, 다른 Runner)이 상태를 망가뜨리지 않는지 본다. */
class TaskFailurePathTest {

    private val demo = Project("demo", "demo", null)
    private val other = Project("other", "other", null)
    private lateinit var service: TaskService
    private lateinit var task: Task

    @BeforeEach
    fun setUp() {
        service = TaskService(ModelSelector(NoAssessor, ModelTier.SONNET), NoStore, MutableClock(), "medium")
        service.poll("r1", listOf(demo))
        task = service.create("demo", "slug", "slugify 추가")
    }

    private fun poll(runner: String = "r1", project: Project = demo) = service.poll(runner, listOf(project))

    private fun pass() = ReportStageCommand(passed = true, retryable = false, diff = "diff", branch = "b")

    private fun fail(retryable: Boolean, error: String) =
        ReportStageCommand(passed = false, retryable = retryable, error = error, branch = "b")

    private fun block() {
        poll()
        service.report(task.id.value, fail(retryable = false, error = "복구 불가"))
    }

    @Test
    fun `실행 중이 아닌 단계에는 결과를 보고할 수 없다`() {
        assertThatThrownBy { service.report(task.id.value, pass()) }.isInstanceOf(InvalidTaskStateException::class.java)

        poll()
        service.report(task.id.value, pass())
        assertThatThrownBy { service.report(task.id.value, pass()) }.isInstanceOf(InvalidTaskStateException::class.java)
        assertThat(service.latest(task).current()!!.status).isEqualTo(StageStatus.WAITING_APPROVAL)
    }

    @Test
    fun `승인 대기가 아니면 승인과 거절을 받지 않는다`() {
        assertThatThrownBy { service.approve(task.id.value) }.isInstanceOf(InvalidTaskStateException::class.java)
        assertThatThrownBy { service.reject(task.id.value, "사유") }.isInstanceOf(InvalidTaskStateException::class.java)

        block()
        assertThatThrownBy { service.approve(task.id.value) }.isInstanceOf(InvalidTaskStateException::class.java)
        assertThat(service.latest(task).status).isEqualTo(TaskStatus.BLOCKED)
    }

    @Test
    fun `거절은 사유가 있어야 하고 사유가 없으면 단계 상태가 그대로다`() {
        poll()
        service.report(task.id.value, pass())

        listOf(null, "", "   ").forEach { feedback ->
            assertThatThrownBy { service.reject(task.id.value, feedback) }.isInstanceOf(InvalidTaskRequestException::class.java)
        }
        assertThat(service.latest(task).current()!!.status).isEqualTo(StageStatus.WAITING_APPROVAL)
    }

    @Test
    fun `차단되지 않은 작업은 재시도할 수 없다`() {
        assertThatThrownBy { service.retry(task.id.value) }.isInstanceOf(InvalidTaskStateException::class.java)
        poll()
        assertThatThrownBy { service.retry(task.id.value) }.isInstanceOf(InvalidTaskStateException::class.java)
    }

    @Test
    fun `없는 작업은 모든 동작에서 찾을 수 없다고 답한다`() {
        assertThatThrownBy { service.report("none", pass()) }.isInstanceOf(TaskNotFoundException::class.java)
        assertThatThrownBy { service.approve("none") }.isInstanceOf(TaskNotFoundException::class.java)
        assertThatThrownBy { service.reject("none", "사유") }.isInstanceOf(TaskNotFoundException::class.java)
        assertThatThrownBy { service.retry("none") }.isInstanceOf(TaskNotFoundException::class.java)
        service.addEvent("none", null, "log", "무시")
    }

    @Test
    fun `차단된 작업과 대기 중인 작업은 배정하지 않는다`() {
        block()
        assertThat(poll()).isNull()

        service.retry(task.id.value)
        assertThat(poll()).isNotNull()
        service.report(task.id.value, pass())
        assertThat(poll()).isNull()
    }

    @Test
    fun `실행 중인 단계는 다른 Runner 에 중복 배정하지 않는다`() {
        assertThat(poll("r1")).isNotNull()
        assertThat(poll("r2")).isNull()
    }

    @Test
    fun `프로젝트를 등록하지 않은 Runner 는 작업을 받지 못한다`() {
        assertThat(poll("r2", other)).isNull()
        assertThat(service.latest(task).status).isEqualTo(TaskStatus.ACTIVE)
        assertThat(service.latest(task).current()!!.status).isEqualTo(StageStatus.PENDING)
    }

    @Test
    fun `실패해도 이미 통과한 단계는 유지하고 재시도하면 실패한 단계부터 다시 한다`() {
        poll(); service.report(task.id.value, pass()); service.approve(task.id.value)
        block()

        assertThat(service.latest(task).stage(Stage.REQUIREMENTS).status).isEqualTo(StageStatus.PASSED)
        service.retry(task.id.value)
        assertThat(poll()!!.stage).isEqualTo(Stage.TEST_CASES.name)
    }

    @Test
    fun `서버가 재시작해 대기로 되돌린 단계에 옛 Runner 가 보고하면 거절한다`() {
        poll()
        val recovered = service.latest(task).recoverAfterRestart()
        val store = object : TaskStore by NoStore {
            override fun loadTasks(): List<Task> = listOf(recovered)
        }
        val restarted = TaskService(ModelSelector(NoAssessor, ModelTier.SONNET), store, MutableClock(), "medium").also { it.restore() }
        assertThat(restarted.latest(task).current()!!.status).isEqualTo(StageStatus.PENDING)
        assertThatThrownBy { restarted.report(task.id.value, pass()) }.isInstanceOf(InvalidTaskStateException::class.java)
        assertThat(restarted.poll("r1", listOf(demo))).isNotNull()
    }

    @Test
    fun `승인 거절 재실행을 반복해도 시도 횟수 때문에 차단되지 않는다`() {
        repeat(Task.MAX_ATTEMPTS + 2) {
            assertThat(poll()).isNotNull()
            service.report(task.id.value, pass())
            service.reject(task.id.value, "다시 $it")
        }
        assertThat(service.latest(task).status).isEqualTo(TaskStatus.ACTIVE)
    }
}
