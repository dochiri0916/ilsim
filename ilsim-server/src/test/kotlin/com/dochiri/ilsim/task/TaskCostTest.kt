package com.dochiri.ilsim.task

import com.dochiri.ilsim.task.application.command.ReportStageCommand
import com.dochiri.ilsim.task.application.usecase.TaskService
import com.dochiri.ilsim.task.domain.ModelTier
import com.dochiri.ilsim.task.domain.Project
import com.dochiri.ilsim.task.domain.vo.Stage
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
        service = TaskService(NoStore, MutableClock())
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
        poll(); service.report(task.id.value, fail(0.07, "claude-sonnet-5-5", "a"))
        poll(); service.report(task.id.value, fail(0.26, "claude-opus-5-5", "b"))
        poll(); service.report(task.id.value, pass(0.30, "claude-opus-5-5"))

        val stage = service.latest(task).stage(Stage.REQUIREMENTS)
        assertThat(stage.costUsd).isCloseTo(0.63, org.assertj.core.data.Offset.offset(1e-9))
        assertThat(stage.costByModel["claude-sonnet-5-5"]).isCloseTo(0.07, org.assertj.core.data.Offset.offset(1e-9))
        assertThat(stage.costByModel["claude-opus-5-5"]).isCloseTo(0.56, org.assertj.core.data.Offset.offset(1e-9))
    }

    @Test
    fun `작업 비용은 단계 비용의 합이고 모델별 내역도 합산한다`() {
        poll(); service.report(task.id.value, pass(0.10)); service.approve(task.id.value)
        poll(); service.report(task.id.value, pass(0.20, "claude-opus-5-5"))

        assertThat(service.latest(task).costUsd()).isCloseTo(0.30, org.assertj.core.data.Offset.offset(1e-9))
        assertThat(service.latest(task).costByModel().keys).containsExactlyInAnyOrder("claude-sonnet-5-5", "claude-opus-5-5")
    }

    @Test
    fun `비용을 모르거나 이상한 값이면 더하지 않고 모델을 모르면 unknown 으로 센다`() {
        poll(); service.report(task.id.value, fail(null, null, "a"))
        poll(); service.report(task.id.value, fail(-1.0, "m", "b"))
        poll(); service.report(task.id.value, fail(Double.NaN, "m", "c"))
        assertThat(service.latest(task).costUsd()).isZero()

        service.retry(task.id.value)
        poll(); service.report(task.id.value, pass(0.05, null))
        assertThat(service.latest(task).costByModel()).containsOnlyKeys(Task.UNKNOWN_MODEL)
    }

    @Test
    fun `검토 호출 비용은 작성 모델과 따로 검토 모델에 더한다`() {
        poll()
        service.report(
            task.id.value,
            ReportStageCommand(
                passed = true, retryable = false, diff = "d", branch = "b",
                costUsd = 0.26, model = "claude-opus-5-5", reviewCostUsd = 0.03, reviewModel = "claude-sonnet-5-5",
            ),
        )
        val stage = service.latest(task).stage(Stage.REQUIREMENTS)
        assertThat(stage.costUsd).isCloseTo(0.29, org.assertj.core.data.Offset.offset(1e-9))
        assertThat(stage.costByModel["claude-opus-5-5"]).isCloseTo(0.26, org.assertj.core.data.Offset.offset(1e-9))
        assertThat(stage.costByModel["claude-sonnet-5-5"]).isCloseTo(0.03, org.assertj.core.data.Offset.offset(1e-9))
    }

    @Test
    fun `통계는 모델별 단계 수와 비용, 단계별 평균 시도, 승격된 단계 수를 센다`() {
        poll(); service.report(task.id.value, fail(0.07, "claude-sonnet-5-5", "a"))
        poll(); service.report(task.id.value, pass(0.26, "claude-opus-5-5")); service.approve(task.id.value)
        poll(); service.report(task.id.value, pass(0.10, "claude-sonnet-5-5"))

        val stats = service.stats()
        assertThat(stats.tasks).isEqualTo(1)
        assertThat(stats.costUsd).isCloseTo(0.43, org.assertj.core.data.Offset.offset(1e-9))
        assertThat(stats.byModel.getValue("claude-sonnet-5-5").stages).isEqualTo(2)
        assertThat(stats.byModel.getValue("claude-sonnet-5-5").costUsd).isCloseTo(0.17, org.assertj.core.data.Offset.offset(1e-9))
        assertThat(stats.byModel.getValue("claude-opus-5-5").stages).isEqualTo(1)
        assertThat(stats.escalatedStages).isEqualTo(1)
        assertThat(service.stats(task.projectId.value).tasks).isEqualTo(1)
        assertThat(service.stats("other").tasks).isEqualTo(0)
        assertThat(stats.byStage.map { it.stage }).containsExactly(Stage.REQUIREMENTS, Stage.TEST_CASES)
        assertThat(stats.byStage.first().averageAttempts).isEqualTo(2.0)
    }

    @Test
    fun `작업이 없으면 통계는 비어 있다`() {
        val empty = TaskService(NoStore, MutableClock()).stats()
        assertThat(empty.tasks).isZero()
        assertThat(empty.byModel).isEmpty()
        assertThat(empty.byStage).isEmpty()
    }
}
