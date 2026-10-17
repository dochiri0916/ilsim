package com.dochiri.ilsim.task

import com.dochiri.ilsim.task.application.command.ReportStageCommand
import com.dochiri.ilsim.task.application.usecase.ModelSelector
import com.dochiri.ilsim.task.application.usecase.TaskService
import com.dochiri.ilsim.task.domain.ModelTier
import com.dochiri.ilsim.task.domain.Project
import com.dochiri.ilsim.task.domain.Stage
import com.dochiri.ilsim.task.domain.Task
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TaskCostTest {

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

    private fun pass(cost: Double?, model: String? = "claude-sonnet-5-5") =
        ReportStageCommand(passed = true, retryable = false, diff = "d", branch = "b", costUsd = cost, model = model)

    private fun fail(cost: Double?, model: String?, error: String) =
        ReportStageCommand(passed = false, retryable = true, error = error, branch = "b", costUsd = cost, model = model)

    @Test
    fun `실패한 시도와 재시도 비용도 단계 비용에 더하고 모델별로 나눠 센다`() {
        poll(); service.report(task.id, fail(0.07, "claude-sonnet-5-5", "a"))
        poll(); service.report(task.id, fail(0.26, "claude-opus-5-5", "b"))
        poll(); service.report(task.id, pass(0.30, "claude-opus-5-5"))

        val stage = task.stage(Stage.REQUIREMENTS)
        assertThat(stage.costUsd).isCloseTo(0.63, org.assertj.core.data.Offset.offset(1e-9))
        assertThat(stage.costByModel["claude-sonnet-5-5"]).isCloseTo(0.07, org.assertj.core.data.Offset.offset(1e-9))
        assertThat(stage.costByModel["claude-opus-5-5"]).isCloseTo(0.56, org.assertj.core.data.Offset.offset(1e-9))
    }

    @Test
    fun `작업 비용은 단계 비용의 합이고 모델별 내역도 합산한다`() {
        poll(); service.report(task.id, pass(0.10)); service.approve(task.id)
        poll(); service.report(task.id, pass(0.20, "claude-opus-5-5"))

        assertThat(task.costUsd()).isCloseTo(0.30, org.assertj.core.data.Offset.offset(1e-9))
        assertThat(task.costByModel().keys).containsExactlyInAnyOrder("claude-sonnet-5-5", "claude-opus-5-5")
    }

    @Test
    fun `비용을 모르거나 이상한 값이면 더하지 않고 모델을 모르면 unknown 으로 센다`() {
        poll(); service.report(task.id, fail(null, null, "a"))
        poll(); service.report(task.id, fail(-1.0, "m", "b"))
        poll(); service.report(task.id, fail(Double.NaN, "m", "c"))
        assertThat(task.costUsd()).isZero()

        service.retry(task.id)
        poll(); service.report(task.id, pass(0.05, null))
        assertThat(task.costByModel()).containsOnlyKeys(Task.UNKNOWN_MODEL)
    }
}
