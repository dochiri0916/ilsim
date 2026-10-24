package com.dochiri.ilsim.task

import com.dochiri.ilsim.task.adapter.out.persistence.TaskRecord
import com.dochiri.ilsim.task.application.command.ReportStageCommand
import com.dochiri.ilsim.task.application.usecase.TaskService
import com.dochiri.ilsim.task.domain.*
import com.dochiri.ilsim.task.domain.vo.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset.offset
import org.junit.jupiter.api.Test

class AiCostOptimizationTest {
    private val project = Project("demo", "demo", null)
    private fun service() = TaskService(NoStore, MutableClock()).also { it.poll("r", listOf(project)) }
    private fun usage(id: String, usd: Double?, session: String = "session") = AiUsage(id, "WRITE", ModelTier.SONNET.modelId,
        sessionId = session, cliVersion = "2.1.302", cumulative = true, costUsd = usd, inputTokens = ((usd ?: 0.0) * 1000).toLong())

    @Test
    fun `세션 누적 비용과 토큰은 차액만 반영하고 저장 복원 뒤에도 유지한다`() {
        val s = service()
        val task = s.create("demo", "slug", "기능")
        val first = s.poll("r", listOf(project))!!
        s.report(task.id.value, ReportStageCommand(true, false, executionId = first.executionId, stage = first.stage, sessionId = "session", usage = listOf(usage("a", 0.10))))
        s.message(task.id.value, "조금 수정")
        val second = s.poll("r", listOf(project))!!
        s.report(task.id.value, ReportStageCommand(true, false, executionId = second.executionId, stage = second.stage, sessionId = "session", usage = listOf(usage("b", 0.15))))
        val restored = TaskRecord.from(s.latest(task)).toTask()
        assertThat(restored.costUsd()).isCloseTo(0.15, offset(1e-9))
        assertThat(restored.current()!!.usage.sumOf { it.countedInputTokens }).isEqualTo(150)
        assertThat(restored.current()!!.usage.last().chargedUsd).isCloseTo(0.05, offset(1e-9))
        assertThat(restored.current()!!.executions).hasSize(2)
    }

    @Test
    fun `응답 유실로 재전송한 완료 결과는 다음 단계 승인 뒤에도 중복 집계하지 않는다`() {
        val s = service(); val task = s.create("demo", "slug", "기능")
        val assignment = s.poll("r", listOf(project))!!
        val result = ReportStageCommand(true, false, executionId = assignment.executionId, stage = assignment.stage, usage = listOf(usage("a", 0.1)))
        s.report(task.id.value, result); s.approve(task.id.value)
        s.poll("r", listOf(project))
        s.report(task.id.value, result)
        assertThat(s.latest(task).costUsd()).isCloseTo(0.1, offset(1e-9))
        assertThat(s.latest(task).current()!!.stage).isEqualTo(Stage.TEST_CASES)
        assertThat(s.latest(task).current()!!.status).isEqualTo(StageStatus.RUNNING)
    }

    @Test
    fun `중지 뒤 새 배정에 늦은 결과가 와도 새 실행의 상태와 세션을 덮지 않는다`() {
        val s = service(); val task = s.create("demo", "slug", "기능")
        val old = s.poll("r", listOf(project))!!
        s.stop(task.id.value); s.retry(task.id.value)
        val next = s.poll("r", listOf(project))!!
        val late = ReportStageCommand(true, false, executionId = old.executionId, stage = old.stage, sessionId = "old", usage = listOf(usage("a", 0.1)))
        s.report(task.id.value, late); s.report(task.id.value, late)
        assertThat(s.latest(task).current()!!.status).isEqualTo(StageStatus.RUNNING)
        assertThat(s.latest(task).current()!!.executionId).isEqualTo(next.executionId)
        assertThat(s.latest(task).current()!!.sessionId).isNull()
        assertThat(s.latest(task).costUsd()).isCloseTo(0.1, offset(1e-9))
    }

    @Test
    fun `Opus 작성과 Sonnet 검토를 모델 승격으로 세지 않고 미확인 비용은 하한으로 표시한다`() {
        val s = service(); val task = s.create("demo", "slug", "기능")
        val assignment = s.poll("r", listOf(project))!!
        s.report(task.id.value, ReportStageCommand(true, false, executionId = assignment.executionId, stage = assignment.stage, model = ModelTier.OPUS.modelId,
            usage = listOf(usage("a", 0.2).copy(model = ModelTier.OPUS.modelId), usage("b", 0.01).copy(purpose = "REVIEW", cumulative = false), usage("c", null).copy(purpose = "JEV"))))
        val stats = s.stats()
        assertThat(stats.escalatedStages).isZero()
        assertThat(stats.unknownCalls).isEqualTo(1)
        assertThat(stats.costIsLowerBound).isTrue()
        assertThat(stats.costUsd).isCloseTo(0.21, offset(1e-9))
        assertThat(stats.byPurpose).containsKeys("WRITE", "REVIEW", "JEV")
    }

    @Test
    fun `세션 reset의 감소값은 절감으로 간주하지 않고 이전 CLI의 독립 비용은 합산한다`() {
        val first = usage("a", 0.1).accounted(emptyList())
        val reset = usage("b", 0.03).accounted(listOf(first))
        assertThat(reset.status).isEqualTo("UNKNOWN")
        assertThat(reset.chargedUsd).isNull()
        assertThat(usage("c", 0.15).copy(cumulative = false, cliVersion = "2.1.200").accounted(listOf(first)).chargedUsd).isEqualTo(0.15)
    }

    @Test
    fun `나중에 확인한 회귀 피드백은 저장 후에도 선택 보정 자료에 남는다`() {
        val s = service(); val task = s.create("demo", "slug", "기능")
        val assignment = s.poll("r", listOf(project))!!
        s.report(task.id.value, ReportStageCommand(true, false, executionId = assignment.executionId, stage = assignment.stage, usage = listOf(usage("a", 0.1))))
        s.approve(task.id.value)
        s.qualityFeedback(task.id.value, assignment.executionId!!, false, "회귀 테스트에서 누락 발견")
        val restored = TaskRecord.from(s.latest(task)).toTask().stage(Stage.REQUIREMENTS).executions.single()
        assertThat(restored.passed).isFalse()
        assertThat(restored.failureKind).isEqualTo("REGRESSION")
        assertThat(restored.validation["user-feedback"]).contains("누락 발견")
    }

    @Test
    fun `금액 알림은 중복 보고에 한 번만 남기고 실행을 중단하지 않는다`() {
        val s = TaskService(NoStore, MutableClock(), costAlertUsd = 0.05)
        s.poll("r", listOf(project)); val task = s.create("demo", "slug", "기능")
        val assignment = s.poll("r", listOf(project))!!
        val result = ReportStageCommand(true, false, executionId = assignment.executionId, stage = assignment.stage, usage = listOf(usage("a", 0.1)))
        repeat(2) { s.report(task.id.value, result) }
        assertThat(s.events(task.id.value).count { it.type == "cost-alert" }).isEqualTo(1)
        assertThat(s.latest(task).status).isEqualTo(TaskStatus.ACTIVE)
    }

    @Test
    fun `이전 형식의 집계를 실제 지출 하한이나 절감 기준으로 단정하지 않는다`() {
        val s = service(); val task = s.create("demo", "slug", "기능")
        s.poll("r", listOf(project))
        s.report(task.id.value, ReportStageCommand(true, false, costUsd = 0.25, model = ModelTier.SONNET.modelId))
        assertThat(s.stats().legacyCostUsd).isEqualTo(0.25)
        assertThat(s.stats().costIsUnverified).isTrue()
        assertThat(s.stats().costIsLowerBound).isFalse()
    }
}
