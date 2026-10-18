package com.dochiri.ilsim.task

import com.dochiri.ilsim.task.adapter.out.persistence.JpaTaskStore
import com.dochiri.ilsim.task.adapter.out.persistence.TaskRecord
import com.dochiri.ilsim.task.application.command.ReportStageCommand
import com.dochiri.ilsim.task.application.port.out.StoredProject
import com.dochiri.ilsim.task.application.port.out.TaskStore
import com.dochiri.ilsim.task.application.usecase.ModelSelector
import com.dochiri.ilsim.task.application.usecase.TaskService
import com.dochiri.ilsim.task.domain.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

class ReviewCostPolicyTest {
    private val policy = ReviewCostPolicy()
    private fun sample(id: String, model: String, quality: ReviewQuality? = ReviewQuality(true, reason = "검증용 정답과 대조")) =
        AiUsage(id, "CHANGE_AUDIT", model, costUsd = if (model == ModelTier.HAIKU.modelId) 0.001 else 0.02,
            review = ReviewEvidence(id, "IMPLEMENTATION:small", policy.version, "input-$id", quality = quality)).accounted(emptyList())
    private fun labeled() = (1..5).map { sample("s$it", ModelTier.SONNET.modelId) } + (1..5).map { sample("h$it", ModelTier.HAIKU.modelId) }

    @Test
    fun `작성 성공이나 JEV 자기 확신만으로 검토 조합을 승인하지 않는다`() {
        val unverified = labeled().map { it.copy(review = it.review!!.copy(quality = null)) }
        assertThat(policy.snapshot(unverified).profiles).isEmpty()
        assertThat(policy.snapshot(labeled().take(5) + labeled().drop(5).take(4)).profiles.none { it.model == ModelTier.HAIKU.modelId && it.eligible }).isTrue()
        assertThat(policy.snapshot(labeled()).profiles.single { it.model == ModelTier.HAIKU.modelId }.eligible).isTrue()
        assertThat(policy.snapshot(labeled().map { it.copy(status = "UNKNOWN", chargedUsd = null) }).profiles).isEmpty()
    }

    @Test
    fun `새 누락과 오탐은 다음 비교 주기 전에도 저가 검토 채택을 철회한다`() {
        for (quality in listOf(ReviewQuality(false, missed = true, reason = "누락 재현"), ReviewQuality(false, falsePositive = true, reason = "오탐 확인"))) {
            val profiles = policy.snapshot(labeled() + sample("bad", ModelTier.HAIKU.modelId, quality)).profiles
            assertThat(profiles.single { it.model == ModelTier.HAIKU.modelId }.eligible).isFalse()
        }
        assertThat(policy.copy(calibrate = false).snapshot(labeled()).profiles).isEmpty()
        assertThat(policy.copy(version = "rollback").snapshot(labeled()).profiles).isEmpty()
    }

    @Test
    fun `한 호출의 여러 사용량을 독립 품질 표본으로 세지 않는다`() {
        val first = sample("h", ModelTier.HAIKU.modelId)
        val duplicate = first.copy(id = "part")
        val snapshot = policy.copy(comparisonEvery = 1).snapshot(listOf(first, duplicate))
        assertThat(snapshot.profiles.single().samples).isEqualTo(1)
        assertThat(snapshot.profiles.single().meanCostUsd).isEqualTo(0.002)
        assertThat(policy.snapshot(labeled().map { it.copy(purpose = "RISK_REVIEW") }).profiles.all { it.purpose == "RISK_REVIEW" }).isTrue()
    }

    @Test
    fun `검토 피드백과 원문은 저장 복원되며 비용 자체를 바꾸지 않는다`() {
        val service = TaskService(ModelSelector(NoAssessor, ModelTier.SONNET), NoStore, MutableClock(), "medium")
        val project = Project("demo", "demo", null)
        service.poll("r", listOf(project))
        val task = service.create("demo", "feature", "기능")
        val assignment = service.poll("r", listOf(project))!!
        service.report(task.id.value, ReportStageCommand(true, false, stage = assignment.stage, executionId = assignment.executionId,
            usage = listOf(sample("review", ModelTier.SONNET.modelId, null).copy(review = sample("review", ModelTier.SONNET.modelId, null).review!!.copy(response = "검토 원문")))))
        val before = service.latest(task).costUsd()
        service.reviewFeedback(task.id.value, "review", ReviewQuality(false, missed = true, reason = "회귀 검증"))
        val restored = TaskRecord.from(service.latest(task)).toTask().stages.flatMap { it.usage }.single()
        assertThat(restored.review!!.quality!!.missed).isTrue()
        assertThat(restored.review.response).isEqualTo("검토 원문")
        assertThat(service.latest(task).costUsd()).isEqualTo(before)
        assertThatThrownBy { service.reviewFeedback(task.id.value, "review", ReviewQuality(true, missed = true, reason = "모순")) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `유료 표본은 주기와 유한한 횟수 및 예약 예산 안에서만 발급한다`() {
        assertThat(policy.grant("s", "e", 20, emptyList())).isNull()
        val sampling = policy.copy(sampleLimit = 3, sampleBudgetUsd = 0.1)
        assertThat(sampling.grant("s", "e", 19, emptyList())).isNull()
        val first = sampling.grant("a", "e", 20, emptyList())!!
        val second = sampling.grant("b", "e2", 40, listOf(first))!!
        assertThat(sampling.grant("c", "e3", 60, listOf(first, second))).isNull()
        assertThat(sampling.copy(sampleLimit = 1).grant("b", "e2", 40, listOf(first))).isNull()
        assertThat(sampling.copy(sampleBudgetUsd = Double.NaN).grant("x", "y", 20, emptyList())).isNull()
    }

    @Test
    fun `작업 삭제와 서버 재시작 뒤에도 표본 예산 예약을 재사용하지 않는다`() {
        val store = object : TaskStore by NoStore {
            val savedTasks = linkedMapOf<String, Task>()
            val savedProjects = linkedMapOf<String, StoredProject>()
            override fun saveTask(task: Task) { savedTasks[task.id.value] = task }
            override fun deleteTask(taskId: String) { savedTasks.remove(taskId) }
            override fun loadTasks() = savedTasks.values.toList()
            override fun saveProject(id: String, project: StoredProject) { savedProjects[id] = project }
            override fun loadProjects() = savedProjects
        }
        val sampling = policy.copy(sampleEvery = 1, sampleLimit = 1, sampleBudgetUsd = 0.05)
        fun service() = TaskService(ModelSelector(NoAssessor, ModelTier.SONNET), store, MutableClock(), "medium", reviewCostPolicy = sampling).also { it.restore(); it.poll("r", listOf(Project("demo", "demo", null))) }
        fun codeAssignment(s: TaskService): com.dochiri.ilsim.task.application.result.Assignment {
            val task = s.create("demo", null, "기능")
            repeat(2) {
                val assignment = s.poll("r", listOf(Project("demo", "demo", null)))!!
                s.report(task.id.value, ReportStageCommand(true, false, stage = assignment.stage, executionId = assignment.executionId))
                s.approve(task.id.value)
            }
            return s.poll("r", listOf(Project("demo", "demo", null)))!!
        }
        val first = service()
        val assignment = codeAssignment(first)
        assertThat(assignment.reviewPolicy.sample).isNotNull()
        first.stop(assignment.taskId); first.delete(assignment.taskId)
        assertThat(codeAssignment(service()).reviewPolicy.sample).isNull()
        val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
        val accounting = JpaTaskStore.ProjectAccounting(reviewSamples = store.savedProjects.getValue("demo").reviewSamples)
        assertThat(mapper.readValue(mapper.writeValueAsString(accounting), JpaTaskStore.ProjectAccounting::class.java)).isEqualTo(accounting)
    }
}
