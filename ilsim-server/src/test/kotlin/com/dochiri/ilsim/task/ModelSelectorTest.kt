package com.dochiri.ilsim.task

import com.dochiri.ilsim.task.application.usecase.ModelSelector
import com.dochiri.ilsim.task.domain.Assessment
import com.dochiri.ilsim.task.domain.ModelTier
import com.dochiri.ilsim.task.domain.RoutingPolicy
import com.dochiri.ilsim.task.domain.Stage
import com.dochiri.ilsim.task.domain.Task
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ModelSelectorTest {

    private fun newTask(requirement: String) = Task.create("id-1234567890", MutableClock().now, "demo", "slug", requirement)

    @Test
    fun `정책은 평가 최소 모델 재시도로 등급을 정한다`() {
        fun choose(c: Double, r: Double, attempt: Int, min: ModelTier) = RoutingPolicy.choose(Assessment(c, r), attempt, min)
        assertThat(choose(0.5, 0.2, 1, ModelTier.HAIKU)).isEqualTo(ModelTier.HAIKU)
        assertThat(choose(0.5, 0.2, 1, ModelTier.SONNET)).isEqualTo(ModelTier.SONNET)
        assertThat(choose(1.8, 0.2, 1, ModelTier.HAIKU)).isEqualTo(ModelTier.SONNET)
        assertThat(choose(0.5, 1.7, 1, ModelTier.HAIKU)).isEqualTo(ModelTier.OPUS)
        assertThat(choose(0.5, 0.2, 2, ModelTier.HAIKU)).isEqualTo(ModelTier.SONNET)
        assertThat(choose(0.5, 0.2, 3, ModelTier.HAIKU)).isEqualTo(ModelTier.OPUS)
        assertThat(choose(0.5, 0.2, 2, ModelTier.SONNET)).isEqualTo(ModelTier.OPUS)
    }

    @Test
    fun `쉬운 작업은 최소 모델로 위험한 작업은 상위 모델로 보내고 이력을 남긴다`() {
        val easy = newTask("쉬운 작업")
        assertThat(ModelSelector(FakeAssessor(Assessment(0.4, 0.1)), ModelTier.HAIKU).select(easy, easy.stage(Stage.IMPLEMENTATION), 1))
            .isEqualTo(ModelTier.HAIKU)

        val risky = newTask("결제 취소")
        val state = risky.stage(Stage.IMPLEMENTATION)
        assertThat(ModelSelector(FakeAssessor(Assessment(2.8, 1.9)), ModelTier.SONNET).select(risky, state, 1)).isEqualTo(ModelTier.OPUS)
        assertThat(state.routing).containsEntry("source", "jev").containsEntry("model", "opus")
        assertThat(state.routingLog.first()).contains("시도 1", "opus", "JEV")
    }

    @Test
    fun `같은 단계의 재시도는 평가를 다시 하지 않는다`() {
        val assessor = FakeAssessor(Assessment(0.4, 0.1))
        val selector = ModelSelector(assessor, ModelTier.HAIKU)
        val task = newTask("요구사항")
        val state = task.stage(Stage.IMPLEMENTATION)
        assertThat(selector.select(task, state, 1)).isEqualTo(ModelTier.HAIKU)
        assertThat(selector.select(task, state, 2)).isEqualTo(ModelTier.SONNET)
        assertThat(selector.select(task, state, 3)).isEqualTo(ModelTier.OPUS)
        assertThat(assessor.calls).isEqualTo(1)
    }

    @Test
    fun `JEV 를 쓸 수 없으면 규칙 기반으로 되돌아간다`() {
        val selector = ModelSelector(NoAssessor, ModelTier.SONNET)
        val task = newTask("요구사항")
        val state = task.stage(Stage.IMPLEMENTATION)
        assertThat(selector.select(task, state, 1)).isEqualTo(ModelTier.SONNET)
        assertThat(selector.select(task, state, 3)).isEqualTo(ModelTier.OPUS)
        assertThat(state.routingLog.first()).contains("규칙 기반", "키 없음")
    }
}
