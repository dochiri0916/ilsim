package com.dochiri.ilsim.task

import com.dochiri.ilsim.task.application.usecase.ModelSelector
import com.dochiri.ilsim.task.application.usecase.TaskService
import com.dochiri.ilsim.task.domain.Assessment
import com.dochiri.ilsim.task.domain.CandidateAssessment
import com.dochiri.ilsim.task.domain.CostRoutingPolicy
import com.dochiri.ilsim.task.domain.ExecutionRecord
import com.dochiri.ilsim.task.domain.ModelCatalog
import com.dochiri.ilsim.task.domain.ModelOption
import com.dochiri.ilsim.task.domain.ModelTier
import com.dochiri.ilsim.task.domain.Project
import com.dochiri.ilsim.task.domain.RoutingAdjustment
import com.dochiri.ilsim.task.domain.RoutingAdjustments
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** Sonnet 5.5 → 5.6 처럼 같은 계열 새 버전은 이력·조정을 이어받아 바로 쓰고, Fable 은 받지 않는다. */
class ModelUpgradeTest {

    @AfterEach
    fun tearDown() = ModelCatalog.reset()

    @Test
    fun `같은 계열의 더 높은 버전만 받아들이고 Fable 과 다른 계열은 무시한다`() {
        val service = TaskService(ModelSelector(NoAssessor, ModelTier.SONNET), NoStore, MutableClock(), "medium")

        val accepted = service.reportModels(mapOf("sonnet" to "claude-sonnet-5-6", "opus" to "claude-fable-6", "haiku" to "claude-haiku-5-4", "fable" to "claude-fable-5-1"))

        assertThat(accepted).isEqualTo(mapOf("sonnet" to "claude-sonnet-5-6"))
        assertThat(ModelCatalog.all()).isEqualTo(mapOf("haiku" to "claude-haiku-5-5", "sonnet" to "claude-sonnet-5-6", "opus" to "claude-opus-5-5"))
        assertThat(service.routing().upgrades.single()).contains("claude-sonnet-5-5 → claude-sonnet-5-6")
        assertThat(service.reportModels(mapOf("sonnet" to "claude-sonnet-5-6"))).isEmpty()
    }

    @Test
    fun `새 버전은 이전 버전 실행 이력으로 검증된 조합 자격을 이어받는다`() {
        val old = (1..6).map { ExecutionRecord("e$it", "claude-haiku-5-5", "medium", "cost-v1", completed = true, passed = true, workload = "W", actualCostUsd = 0.001) }
        ModelCatalog.upgrade(ModelTier.HAIKU, "claude-haiku-5-6")

        val route = CostRoutingPolicy(comparisonEvery = 1).choose(Assessment(1.0, 0.0, listOf(CandidateAssessment("claude-haiku-5-6", "medium", 0.99))), ModelTier.HAIKU, 1000, old + sonnetControl(), "W")

        assertThat(route.option).isEqualTo(ModelOption(ModelTier.HAIKU, "medium"))
        assertThat(route.option.key).isEqualTo("claude-haiku-5-6/medium")
    }

    @Test
    fun `조정은 계열 단위라 새 버전에도 그대로 적용되고 잠긴 모델은 조정할 수 없다`() {
        val off = RoutingAdjustments.of(listOf(RoutingAdjustment("claude-haiku-5-5", "low", enabled = false)), null, Instant.now())
        ModelCatalog.upgrade(ModelTier.HAIKU, "claude-haiku-5-6")
        assertThat(off.allows(ModelOption(ModelTier.HAIKU, "low"))).isFalse()

        val service = TaskService(ModelSelector(NoAssessor, ModelTier.SONNET), NoStore, MutableClock(), "medium")
        org.assertj.core.api.Assertions.assertThatThrownBy { service.adjust(listOf(RoutingAdjustment("claude-fable-5-1", "low", enabled = true, successOffset = 0.1)), null) }
            .hasMessageContaining("잠긴")
    }

    @Test
    fun `새 버전을 받으면 다음 배정부터 그 ID 로 보낸다`() {
        val service = TaskService(ModelSelector(NoAssessor, ModelTier.SONNET), NoStore, MutableClock(), "medium")
        val project = Project("demo", "demo", null)
        service.poll("r1", listOf(project))
        service.create("demo", "slug", "기능")
        service.reportModels(mapOf("sonnet" to "claude-sonnet-5-6", "opus" to "claude-opus-5-6"))

        val assignment = service.poll("r1", listOf(project))!!

        assertThat(assignment.model).isIn("claude-sonnet-5-6", "claude-opus-5-6")
    }

    /** 저가 조합을 채택하려면 같은 작업군의 기준 경로(보수적 조합) 표본도 있어야 한다. */
    private fun sonnetControl() = (1..6).map { ExecutionRecord("c$it", "claude-sonnet-5-5", "medium", "cost-v1", completed = true, passed = true, workload = "W", actualCostUsd = 0.01) }
}
