package com.dochiri.ilsim.task

import com.dochiri.ilsim.task.application.command.ReportStageCommand
import com.dochiri.ilsim.task.application.usecase.ModelSelector
import com.dochiri.ilsim.task.application.usecase.TaskService
import com.dochiri.ilsim.task.domain.ModelTier
import com.dochiri.ilsim.task.domain.Project
import com.dochiri.ilsim.task.domain.Task
import com.dochiri.ilsim.task.domain.exception.InvalidTaskStateException
import com.dochiri.ilsim.task.domain.vo.StageStatus
import com.dochiri.ilsim.task.domain.vo.TaskStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** 사용자가 실행 중인 단계를 중지하고, 실패 사유에 지시를 더해 다시 시도하는 흐름. */
class TaskStopRetryTest {

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

    @Test
    fun `실행 중인 단계를 중지하면 차단되고 Runner 에 중지를 알린다`() {
        poll()
        assertThat(service.shouldStop(task.id.value)).isFalse()

        service.stop(task.id.value)

        val stopped = service.latest(task)
        assertThat(stopped.status).isEqualTo(TaskStatus.BLOCKED)
        assertThat(stopped.current()!!.status).isEqualTo(StageStatus.FAILED)
        assertThat(stopped.current()!!.error).isEqualTo(Task.STOPPED_MESSAGE)
        assertThat(service.shouldStop(task.id.value)).isTrue()
        assertThat(service.events(task.id.value).last().data).isEqualTo(Task.STOPPED_MESSAGE)
    }

    @Test
    fun `대기 중인 단계도 중지할 수 있고 차단된 작업은 다시 중지할 수 없다`() {
        service.stop(task.id.value)
        assertThat(service.latest(task).status).isEqualTo(TaskStatus.BLOCKED)
        assertThatThrownBy { service.stop(task.id.value) }.isInstanceOf(InvalidTaskStateException::class.java)
    }

    @Test
    fun `중지 뒤 늦게 온 결과는 비용만 남기고 상태를 바꾸지 않는다`() {
        poll()
        service.stop(task.id.value)

        service.report(task.id.value, ReportStageCommand(passed = true, retryable = false, costUsd = 0.5, model = "m"))

        val after = service.latest(task)
        assertThat(after.status).isEqualTo(TaskStatus.BLOCKED)
        assertThat(after.current()!!.status).isEqualTo(StageStatus.FAILED)
        assertThat(after.costUsd()).isEqualTo(0.5)
    }

    @Test
    fun `답하며 다시 시도하면 직전 Claude 세션을 이어 실패 사유와 사용자 말을 전한다`() {
        poll()
        service.report(task.id.value, ReportStageCommand(passed = false, retryable = false, error = "실패한 테스트 1건", sessionId = "s-1"))

        service.retry(task.id.value, "  null 입력도 처리해  ")

        assertThat(service.latest(task).status).isEqualTo(TaskStatus.ACTIVE)
        val assignment = poll()!!
        assertThat(assignment.resumeSessionId).isEqualTo("s-1")
        assertThat(assignment.prompt).contains("null 입력도 처리해").contains("실패한 테스트 1건")
        assertThat(service.events(task.id.value).map { it.type to it.data }).contains("message" to "null 입력도 처리해")
    }

    @Test
    fun `말 없이 다시 시도하면 새 세션에서 실패 사유만 넘긴다`() {
        poll()
        service.report(task.id.value, ReportStageCommand(passed = false, retryable = false, error = "실패한 테스트 1건", sessionId = "s-1"))

        service.retry(task.id.value)

        val assignment = poll()!!
        assertThat(assignment.resumeSessionId).isNull()
        assertThat(assignment.prompt).contains("실패한 테스트 1건")
    }

    @Test
    fun `중지한 단계에 답하면 중지 문구는 넘기지 않는다`() {
        poll()
        service.stop(task.id.value)

        service.retry(task.id.value, "범위를 줄여")

        assertThat(poll()!!.prompt).doesNotContain(Task.STOPPED_MESSAGE).contains("범위를 줄여")
    }
}
