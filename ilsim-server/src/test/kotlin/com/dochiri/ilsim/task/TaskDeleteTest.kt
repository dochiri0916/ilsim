package com.dochiri.ilsim.task

import com.dochiri.ilsim.task.application.command.ReportStageCommand
import com.dochiri.ilsim.task.application.usecase.ModelSelector
import com.dochiri.ilsim.task.application.usecase.TaskService
import com.dochiri.ilsim.task.domain.ModelTier
import com.dochiri.ilsim.task.domain.Project
import com.dochiri.ilsim.task.domain.exception.InvalidTaskStateException
import com.dochiri.ilsim.task.domain.exception.TaskNotFoundException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** 작업 삭제와, 그 worktree 를 Runner 가 지우도록 넘기는 정리 일감. */
class TaskDeleteTest {

    private val demo = Project("demo", "demo", null)
    private val service = TaskService(ModelSelector(NoAssessor, ModelTier.SONNET), NoStore, MutableClock(), "medium").also {
        it.poll("r1", listOf(demo))
    }

    @Test
    fun `실행 중인 작업은 지울 수 없고 중지하면 지울 수 있다`() {
        val task = service.create("demo", "flow", "플로우 알려줘")
        service.poll("r1", listOf(demo))

        assertThatThrownBy { service.delete(task.id.value) }.isInstanceOf(InvalidTaskStateException::class.java)

        service.stop(task.id.value)
        service.delete(task.id.value)

        assertThat(service.find(task.id.value)).isNull()
        assertThat(service.list()).isEmpty()
        assertThatThrownBy { service.delete(task.id.value) }.isInstanceOf(TaskNotFoundException::class.java)
    }

    @Test
    fun `끝나지 않은 작업은 브랜치까지, 끝난 작업은 worktree 만 지우도록 한 번만 넘긴다`() {
        val open = service.create("demo", "open", "하다 만 작업")
        assertThat(service.poll("r1", listOf(demo))!!.taskId).isEqualTo(open.id.value)
        service.report(open.id.value, ReportStageCommand(passed = false, retryable = false, error = "실패", branch = "ilsim/open-x"))
        val done = service.create("demo", "done", "질문")
        assertThat(service.poll("r1", listOf(demo))!!.taskId).isEqualTo(done.id.value)
        service.report(done.id.value, ReportStageCommand(passed = true, retryable = false, answered = true, branch = "ilsim/done-y"))

        service.delete(open.id.value)
        service.delete(done.id.value)

        assertThat(service.claimCleanups(listOf("other"))).isEmpty()
        val jobs = service.claimCleanups(listOf("demo")).associateBy { it.taskId }
        assertThat(jobs[open.id.value]!!.keepBranch).isFalse()
        assertThat(jobs[open.id.value]!!.branch).isEqualTo("ilsim/open-x")
        assertThat(jobs[done.id.value]!!.keepBranch).isTrue()
        assertThat(service.claimCleanups(listOf("demo"))).isEmpty()
    }
}
