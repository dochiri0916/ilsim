package com.dochiri.ilsim.task

import com.dochiri.ilsim.task.adapter.out.persistence.TaskRecord
import com.dochiri.ilsim.task.application.command.ReportStageCommand
import com.dochiri.ilsim.task.application.port.out.StoredProject
import com.dochiri.ilsim.task.application.port.out.TaskStore
import com.dochiri.ilsim.task.application.usecase.ModelSelector
import com.dochiri.ilsim.task.application.usecase.TaskService
import com.dochiri.ilsim.task.domain.ModelTier
import com.dochiri.ilsim.task.domain.Project
import com.dochiri.ilsim.task.domain.vo.Stage
import com.dochiri.ilsim.task.domain.vo.StageStatus
import com.dochiri.ilsim.task.domain.Task
import com.dochiri.ilsim.task.domain.TaskEvent
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

class PersistenceTest {

    /** 저장할 때 실제 저장과 같은 JSON 왕복을 거치는 메모리 저장소. */
    private class MemoryStore : TaskStore {
        val json: JsonMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
        val tasks = LinkedHashMap<String, String>()
        val events = LinkedHashMap<String, MutableList<TaskEvent>>()
        val projects = LinkedHashMap<String, StoredProject>()
        var down = false
        var projectWrites = 0

        private fun check() = check(!down) { "db down" }

        override fun loadTasks(): List<Task> { check(); return tasks.values.map { json.readValue(it, TaskRecord::class.java).toTask() } }
        override fun saveTask(task: Task) { check(); tasks[task.id.value] = json.writeValueAsString(TaskRecord.from(task)) }
        override fun loadEvents(taskId: String, limit: Int): List<TaskEvent> { check(); return events[taskId].orEmpty().toList() }
        override fun appendEvent(taskId: String, event: TaskEvent) { check(); events.getOrPut(taskId) { ArrayList() }.add(event) }
        override fun loadProjects(): Map<String, StoredProject> { check(); return projects }
        override fun saveProject(id: String, project: StoredProject) { check(); projects[id] = project; projectWrites++ }
    }

    private val demo = Project("demo", "demo", null)
    private val clock = MutableClock()

    private fun service(store: MemoryStore) =
        TaskService(ModelSelector(NoAssessor, ModelTier.SONNET), store, clock, "medium").also { it.restore() }

    @Test
    fun `작업과 단계 결과 이벤트가 저장되고 서버를 다시 띄워도 되살아난다`() {
        val store = MemoryStore()
        val first = service(store)
        first.poll("r1", listOf(demo))
        val task = first.create("demo", "slug", "slugify 추가")
        first.poll("r1", listOf(demo))
        first.addEvent(task.id.value, "REQUIREMENTS", "log", "시작")
        first.report(
            task.id.value,
            ReportStageCommand(true, false, "diff", null, mapOf("specs/slug/requirements.md" to "REQ-1"), mapOf("traceability" to "PASSED"), "branch-1"),
        )
        first.approve(task.id.value)

        val second = service(store)
        val restored = second.find(task.id.value)!!
        assertThat(restored.feature.value).isEqualTo("slug")
        assertThat(restored.requirement.value).isEqualTo("slugify 추가")
        assertThat(restored.createdAt).isEqualTo(task.createdAt)
        assertThat(restored.branch?.value).isEqualTo("branch-1")
        val requirements = restored.stages.first()
        assertThat(requirements.status).isEqualTo(StageStatus.PASSED)
        assertThat(requirements.artifacts).containsEntry("specs/slug/requirements.md", "REQ-1")
        assertThat(requirements.validation).containsEntry("traceability", "PASSED")
        assertThat(restored.current()!!.stage).isEqualTo(Stage.TEST_CASES)
        assertThat(second.events(task.id.value)).extracting<String?> { it.data }.containsExactly("시작", "PASSED\ntraceability=PASSED")
        assertThat(second.projects()).extracting<String> { it.project.id }.containsExactly("demo")
    }

    @Test
    fun `단계 비용은 서버를 다시 띄워도 유지된다`() {
        val store = MemoryStore()
        val first = service(store)
        first.poll("r1", listOf(demo))
        val task = first.create("demo", "slug", "slugify 추가")
        first.poll("r1", listOf(demo))
        first.report(task.id.value, ReportStageCommand(true, false, "diff", null, null, null, "b", 0.42, "claude-sonnet-5-5"))

        val restored = service(store).find(task.id.value)!!
        assertThat(restored.costUsd()).isEqualTo(0.42)
        assertThat(restored.costByModel()).containsEntry("claude-sonnet-5-5", 0.42)
    }

    @Test
    fun `실행 중이던 단계는 대기로 되돌려 다시 배정한다`() {
        val store = MemoryStore()
        val first = service(store)
        first.poll("r1", listOf(demo))
        val task = first.create("demo", "slug", "x")
        assertThat(first.poll("r1", listOf(demo))).isNotNull()
        assertThat(first.latest(task).current()!!.status).isEqualTo(StageStatus.RUNNING)

        val second = service(store)
        assertThat(second.find(task.id.value)!!.current()!!.status).isEqualTo(StageStatus.PENDING)
        assertThat(second.poll("r1", listOf(demo))).isNotNull()
    }

    @Test
    fun `저장소가 죽으면 새 실행 배정을 보류하고 진행 로그는 메모리에서 유지한다`() {
        val store = MemoryStore().also { it.down = true }
        val service = service(store)
        assertThat(service.list()).isEmpty()
        service.poll("r1", listOf(demo))
        val task = service.create("demo", "slug", "x")
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) { service.poll("r1", listOf(demo)) }
        assertThat(service.find(task.id.value)!!.current()!!.status).isEqualTo(StageStatus.PENDING)
        service.addEvent(task.id.value, "REQUIREMENTS", "log", "계속")
        assertThat(service.events(task.id.value)).hasSize(1)
        assertThat(store.tasks).isEmpty()
    }

    @Test
    fun `Runner 가 매번 poll 해도 프로젝트는 30초에 한 번만 저장하고 최근에 본 프로젝트만 online 이다`() {
        val store = MemoryStore()
        val service = service(store)
        repeat(5) { service.poll("r1", listOf(demo)) }
        assertThat(store.projectWrites).isEqualTo(1)
        assertThat(service.projects().first().online).isTrue()

        store.projects["old"] = StoredProject("old", "r0", clock.now.minus(Duration.ofHours(1)))
        val restarted = service(store)
        assertThat(restarted.projects().single { it.project.id == "old" }.online).isFalse()
    }
}
