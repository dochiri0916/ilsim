package com.dochiri.ilsim.task

import com.dochiri.ilsim.task.application.command.ReportStageCommand
import com.dochiri.ilsim.task.application.usecase.ModelSelector
import com.dochiri.ilsim.task.application.usecase.TaskService
import com.dochiri.ilsim.task.domain.ModelTier
import com.dochiri.ilsim.task.domain.Project
import com.dochiri.ilsim.task.domain.PromptTemplates
import com.dochiri.ilsim.task.domain.vo.Stage
import com.dochiri.ilsim.task.domain.vo.StageStatus
import com.dochiri.ilsim.task.domain.vo.TaskStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TaskAnswerTest {

    private val demo = Project("demo", "demo", null)
    private val service = TaskService(ModelSelector(NoAssessor, ModelTier.SONNET), NoStore, MutableClock(), "medium").also {
        it.poll("r1", listOf(demo))
    }

    @Test
    fun `요구사항 지시문은 질문이면 답만 하고 표시를 남기라고 알려 준다`() {
        service.create("demo", null, "지금 구현된 플로우 알려줘")
        assertThat(service.poll("r1", listOf(demo))!!.prompt).contains(PromptTemplates.ANSWERED_MARK)
    }

    @Test
    fun `답만 했다고 보고하면 남은 단계를 실행하지 않고 작업을 끝낸다`() {
        val task = service.create("demo", null, "지금 구현된 플로우 알려줘")
        service.poll("r1", listOf(demo))

        service.report(task.id.value, ReportStageCommand(passed = true, retryable = false, answered = true))

        val done = service.latest(task)
        assertThat(done.status).isEqualTo(TaskStatus.COMPLETED)
        assertThat(done.stage(Stage.REQUIREMENTS).status).isEqualTo(StageStatus.PASSED)
        assertThat(done.stage(Stage.TEST_CASES).status).isEqualTo(StageStatus.PENDING)
        assertThat(service.poll("r1", listOf(demo))).isNull()
    }
}
