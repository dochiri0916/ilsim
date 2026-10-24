package com.dochiri.ilsim.task

import com.dochiri.ilsim.task.adapter.out.persistence.TaskRecord
import com.dochiri.ilsim.task.application.command.ReportStageCommand
import com.dochiri.ilsim.task.application.usecase.TaskService
import com.dochiri.ilsim.task.domain.ModelTier
import com.dochiri.ilsim.task.domain.Project
import com.dochiri.ilsim.task.domain.Task
import com.dochiri.ilsim.task.domain.exception.InvalidTaskStateException
import com.dochiri.ilsim.task.domain.vo.StageStatus
import com.dochiri.ilsim.task.domain.vo.TaskStatus
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AgentTaskTest {

    private val demo = Project("demo", "demo", null)
    private lateinit var service: TaskService

    @BeforeEach
    fun setUp() {
        service = TaskService(NoStore, MutableClock())
        service.register("r1", listOf(demo))
    }

    private fun pass(executionId: String?, stage: String) =
        ReportStageCommand(passed = true, retryable = false, diff = "diff", branch = "b", executionId = executionId, stage = stage)

    @Test
    fun `Claude 앱 작업은 Runner가 가져가지 않고 claim 으로만 시작한다`() {
        val task = service.createForAgent("demo", "slug", "slugify 추가")
        assertThat(task.agent).isEqualTo(Task.CLAUDE_APP)

        val first = service.claim(task.id.value, "r1")!!
        assertThat(first.stage).isEqualTo("REQUIREMENTS")
        assertThat(first.model).isEqualTo(Task.CLAUDE_APP)
        assertThat(first.prompt).contains("slugify 추가")
        assertThat(service.claim(task.id.value, "r1")!!.executionId).isEqualTo(first.executionId)

        service.report(task.id.value, pass(first.executionId, "REQUIREMENTS"))
        assertThat(service.latest(task).current()!!.status).isEqualTo(StageStatus.WAITING_APPROVAL)
        assertThat(service.claim(task.id.value, "r1")).isNull()

        service.approve(task.id.value)
        val second = service.claim(task.id.value, "r1")!!
        assertThat(second.stage).isEqualTo("TEST_CASES")
        assertThat(second.executionId).isNotEqualTo(first.executionId)
        assertThat(service.latest(task).status).isEqualTo(TaskStatus.ACTIVE)
    }

    @Test
    fun `이전 Runner 작업은 claim 할 수 없다`() {
        val legacy = Task.create(com.dochiri.ilsim.task.domain.vo.TaskId.of("legacy"), MutableClock().now, com.dochiri.ilsim.task.domain.vo.ProjectId.of("demo"), "slug", "x")
        val store = object : com.dochiri.ilsim.task.application.port.out.TaskStore by NoStore {
            override fun loadTasks(): List<Task> = listOf(legacy)
        }
        val restored = TaskService(store, MutableClock()).also { it.restore() }
        assertThatThrownBy { restored.claim("legacy", "r1") }.isInstanceOf(InvalidTaskStateException::class.java)
    }

    @Test
    fun `실행 주체가 없거나 null 인 이전 기록은 Runner 작업으로 읽는다`() {
        val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build()
        val task = service.createForAgent("demo", "slug", "slugify 추가")
        val json = mapper.writeValueAsString(TaskRecord.from(task))
        assertThat(mapper.readValue(json, TaskRecord::class.java).toTask().agent).isEqualTo(Task.CLAUDE_APP)
        val legacy = json.replace("\"agent\":\"claude-app\"", "\"agent\":null")
        assertThat(mapper.readValue(legacy, TaskRecord::class.java).toTask().agent).isEqualTo(Task.RUNNER)
    }
}
