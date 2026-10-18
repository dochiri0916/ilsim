package com.dochiri.ilsim.task

import com.dochiri.ilsim.task.application.command.ReportStageCommand
import com.dochiri.ilsim.task.application.usecase.ModelSelector
import com.dochiri.ilsim.task.application.usecase.TaskService
import com.dochiri.ilsim.task.domain.ModelTier
import com.dochiri.ilsim.task.domain.Project
import com.dochiri.ilsim.task.domain.StageState
import com.dochiri.ilsim.task.domain.vo.Feature
import com.dochiri.ilsim.task.domain.vo.ProjectId
import com.dochiri.ilsim.task.domain.vo.Requirement
import com.dochiri.ilsim.task.domain.vo.RunCount
import com.dochiri.ilsim.task.domain.vo.Stage
import com.dochiri.ilsim.task.domain.vo.TaskId
import com.dochiri.ilsim.task.domain.vo.StageStatus
import com.dochiri.ilsim.task.domain.Task
import com.dochiri.ilsim.task.domain.vo.TaskStatus
import com.dochiri.ilsim.task.domain.exception.InvalidTaskRequestException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TaskServiceTest {

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

    private fun pass(artifacts: Map<String, String> = emptyMap()) =
        ReportStageCommand(passed = true, retryable = false, diff = "diff", artifacts = artifacts, branch = "b")

    private fun fail(retryable: Boolean, error: String = "boom") =
        ReportStageCommand(passed = false, retryable = retryable, error = error, branch = "b")

    private fun toImplementation() {
        poll(); service.report(task.id.value, pass()); service.approve(task.id.value)
        poll(); service.report(task.id.value, pass()); service.approve(task.id.value)
        poll(); service.report(task.id.value, pass())
    }

    @Test
    fun `등록되지 않은 프로젝트나 잘못된 feature는 거절한다`() {
        assertThatThrownBy { service.create("none", "a", "x") }.isInstanceOf(InvalidTaskRequestException::class.java)
        assertThatThrownBy { service.create("demo", "Bad Name", "x") }.isInstanceOf(InvalidTaskRequestException::class.java)
        assertThatThrownBy { service.create("demo", "ok", " ") }.isInstanceOf(InvalidTaskRequestException::class.java)
    }

    @Test
    fun `승인 단계는 승인 전에는 다음 단계로 넘어가지 않는다`() {
        assertThat(poll()!!.stage).isEqualTo("REQUIREMENTS")
        service.report(task.id.value, pass(mapOf("specs/slug/requirements.md" to "REQ-1")))

        assertThat(service.latest(task).current()!!.status).isEqualTo(StageStatus.WAITING_APPROVAL)
        assertThat(service.latest(task).current()!!.artifactHash).isNotBlank()
        assertThat(poll()).isNull()

        service.approve(task.id.value)
        assertThat(poll()!!.stage).isEqualTo("TEST_CASES")
    }

    @Test
    fun `거절하면 피드백과 함께 같은 단계를 다시 실행한다`() {
        poll()
        service.report(task.id.value, pass())
        service.reject(task.id.value, "예외 상황 보강")

        val assignment = poll()!!
        assertThat(assignment.stage).isEqualTo("REQUIREMENTS")
        assertThat(assignment.prompt).contains("예외 상황 보강")
    }

    @Test
    fun `재시도 가능한 실패는 최대 횟수까지 재시도하고 이후 차단한다`() {
        repeat(Task.MAX_ATTEMPTS) {
            assertThat(poll()).isNotNull()
            service.report(task.id.value, fail(true, "사유 $it".repeat(1) + "-" + "x".repeat(it)))
        }
        assertThat(service.latest(task).status).isEqualTo(TaskStatus.BLOCKED)
        assertThat(poll()).isNull()

        service.retry(task.id.value)
        assertThat(service.latest(task).status).isEqualTo(TaskStatus.ACTIVE)
        assertThat(poll()).isNotNull()
    }

    @Test
    fun `재시도 불가 실패는 즉시 차단한다`() {
        poll()
        service.report(task.id.value, fail(false))
        assertThat(service.latest(task).status).isEqualTo(TaskStatus.BLOCKED)
    }

    @Test
    fun `마지막 단계까지 통과하면 완료된다`() {
        toImplementation()
        poll()
        service.report(task.id.value, pass())
        assertThat(service.latest(task).status).isEqualTo(TaskStatus.COMPLETED)
    }

    @Test
    fun `같은 사유로 연달아 실패하면 남은 시도를 건너뛰고 상위 모델로 올린다`() {
        toImplementation()
        val first = poll()!!
        assertThat(first.stage).isEqualTo("IMPLEMENTATION")
        service.report(task.id.value, fail(true, "Foo.kt:12 테스트 실패: expected 3"))

        assertThat(poll()!!.attempt).isEqualTo(2)
        service.report(task.id.value, fail(true, "Foo.kt:48 테스트 실패: expected 7"))

        val third = poll()!!
        assertThat(third.attempt).isEqualTo(Task.MAX_ATTEMPTS)
        assertThat(third.model).isEqualTo("claude-opus-5-5")
        assertThat(third.effort).isEqualTo("medium")
    }

    @Test
    fun `사유가 달라지면 차례대로 시도한다`() {
        toImplementation()
        poll(); service.report(task.id.value, fail(true, "테스트 실패"))
        poll(); service.report(task.id.value, fail(true, "아키텍처 위반"))
        assertThat(poll()!!.attempt).isEqualTo(3)
    }

    @Test
    fun `거절은 사람의 개입이라 실행 횟수가 초기화된다`() {
        repeat(Task.MAX_TASK_RUNS) {
            poll()
            service.report(task.id.value, pass())
            service.reject(task.id.value, "다시")
        }
        assertThat(service.latest(task).runs.value).isZero()
        assertThat(poll()).isNotNull()
    }

    @Test
    fun `작업 전체 실행 횟수 상한에 닿으면 멈추고 재시도하면 초기화된다`() {
        val exhausted = Task.restore(
            id = TaskId.of("id-limit"),
            createdAt = MutableClock().now,
            projectId = ProjectId.of("demo"),
            feature = Feature.of("limit"),
            requirement = Requirement.of("x"),
            status = TaskStatus.ACTIVE,
            runnerId = null,
            branch = null,
            runs = RunCount.of(Task.MAX_TASK_RUNS),
            stages = Stage.entries.map { StageState.initial(it) },
        )
        val store = object : com.dochiri.ilsim.task.application.port.out.TaskStore by NoStore {
            override fun loadTasks(): List<Task> = listOf(exhausted)
        }
        val restored = TaskService(ModelSelector(NoAssessor, ModelTier.SONNET), store, MutableClock(), "medium").also { it.restore() }

        assertThat(restored.poll("r1", listOf(demo))).isNull()
        assertThat(restored.latest(exhausted).status).isEqualTo(TaskStatus.BLOCKED)
        assertThat(restored.latest(exhausted).current()!!.error).contains("상한")

        restored.retry(exhausted.id.value)
        assertThat(restored.latest(exhausted).runs.value).isZero()
        assertThat(restored.poll("r1", listOf(demo))).isNotNull()
    }

    @Test
    fun `JEV 장애는 시도 횟수만으로 모델을 승격하지 않는다`() {
        val selector = ModelSelector(NoAssessor, ModelTier.SONNET)
        val state = service.latest(task).stage(Stage.IMPLEMENTATION)
        assertThat(selector.select(task, state, 1).tier).isEqualTo(ModelTier.SONNET)
        assertThat(selector.select(task, state, 3).tier).isEqualTo(ModelTier.SONNET)
    }
}
