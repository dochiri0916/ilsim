package com.dochiri.ilsim.task

import com.dochiri.ilsim.task.adapter.`in`.web.RoutingController
import com.dochiri.ilsim.task.adapter.out.persistence.TaskRecord
import com.dochiri.ilsim.task.application.port.out.RoutingAdjustmentStore
import com.dochiri.ilsim.task.application.usecase.ModelSelector
import com.dochiri.ilsim.task.application.usecase.TaskService
import com.dochiri.ilsim.task.domain.Assessment
import com.dochiri.ilsim.task.domain.CandidateAssessment
import com.dochiri.ilsim.task.domain.CostRoutingPolicy
import com.dochiri.ilsim.task.domain.ExecutionRecord
import com.dochiri.ilsim.task.domain.ModelOption
import com.dochiri.ilsim.task.domain.ModelTier
import com.dochiri.ilsim.task.domain.Project
import com.dochiri.ilsim.task.domain.RoutingAdjustment
import com.dochiri.ilsim.task.domain.RoutingAdjustments
import com.dochiri.ilsim.task.domain.RoutingReport
import com.dochiri.ilsim.task.domain.SelectionVerdict
import com.dochiri.ilsim.task.domain.Task
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class RoutingReviewTest {

    private val haiku = ModelTier.HAIKU.modelId
    private val sonnet = ModelTier.SONNET.modelId
    private val workload = "IMPLEMENTATION:1:0"
    private var seq = 0

    private fun exec(model: String, effort: String, passed: Boolean, cost: Double?, predicted: Double? = 0.95, failure: String? = if (passed) null else "MODEL") =
        ExecutionRecord("e${seq++}", model, effort, "cost-v1", 0.02, predicted, completed = true, passed = passed, failureKind = failure, workload = workload, actualCostUsd = cost)

    private fun task(vararg executions: ExecutionRecord): Task = TaskRecord("t${seq++}", "demo", "f", "요구", Instant.parse("2026-10-18T00:00:00Z"), "COMPLETED",
        stages = listOf(TaskRecord.StageRecord("IMPLEMENTATION", if (executions.last().passed) "PASSED" else "FAILED", executions = executions.toList()))).toTask()

    @Test
    fun `더 싼 조합이 같은 작업군에서 충분히 성공했으면 비싼 선택은 과하다고 판정한다`() {
        val cheap = (1..5).map { task(exec(haiku, "medium", true, 0.01)) }
        val pricey = task(exec(sonnet, "high", true, 0.10))

        val report = RoutingReport.of(cheap + pricey, CostRoutingPolicy())

        val review = report.recent.first { it.model == sonnet }
        assertThat(review.verdict).isEqualTo(SelectionVerdict.OVER)
        assertThat(review.cheaperOption).isEqualTo("$haiku/medium")
        assertThat(report.workloads.single().cheapestQualified).isEqualTo("$haiku/medium")
    }

    @Test
    fun `실패 뒤 더 높은 조합으로 성공했으면 부족한 선택이고 환경 장애는 판단하지 않는다`() {
        val escalated = task(exec(haiku, "low", false, 0.01), exec(sonnet, "high", true, 0.08))
        val broken = task(exec(haiku, "low", false, 0.0, failure = "NETWORK"))

        val report = RoutingReport.of(listOf(escalated, broken), CostRoutingPolicy())

        assertThat(report.recent.first { it.taskId == escalated.id.value && !it.passed }.verdict).isEqualTo(SelectionVerdict.UNDER)
        assertThat(report.recent.first { it.taskId == broken.id.value }.verdict).isEqualTo(SelectionVerdict.UNKNOWN)
        val low = report.options.first { it.model == haiku && it.effort == "low" }
        assertThat(low.executions).isEqualTo(1)
        assertThat(low.under).isEqualTo(1)
    }

    @Test
    fun `예측이 실제보다 낙관적이면 표본 수만큼 줄인 음수 보정을 권한다`() {
        val tasks = (1..6).map { task(exec(sonnet, "medium", it <= 3, 0.02, predicted = 0.95)) }

        val option = RoutingReport.of(tasks, CostRoutingPolicy()).options.single()

        assertThat(option.passRate).isEqualTo(0.5)
        assertThat(option.calibrationGap).isCloseTo(0.45, org.assertj.core.api.Assertions.within(1e-9))
        assertThat(option.recommendedOffset).isEqualTo(-0.17)
        assertThat(RoutingReport.of(tasks.take(4), CostRoutingPolicy()).options.single().recommendedOffset).isNull()
    }

    @Test
    fun `끈 조합은 고르지 않고 음수 보정으로 품질 기준 아래가 되면 제외한다`() {
        val policy = CostRoutingPolicy(allowUnverified = true)
        val assessment = Assessment(1.0, 0.0, listOf(CandidateAssessment(haiku, "low", 0.99), CandidateAssessment(haiku, "medium", 0.99)))
        assertThat(policy.choose(assessment, ModelTier.HAIKU, 1000, emptyList(), workload).option).isEqualTo(ModelOption(ModelTier.HAIKU, "low"))

        val off = RoutingAdjustments.of(listOf(RoutingAdjustment(haiku, "low", enabled = false)), null, Instant.now())
        assertThat(policy.choose(assessment, ModelTier.HAIKU, 1000, emptyList(), workload, adjustments = off).option).isEqualTo(ModelOption(ModelTier.HAIKU, "medium"))

        val pessimistic = RoutingAdjustments.of(listOf(RoutingAdjustment(haiku, "low", successOffset = -0.2)), null, Instant.now())
        assertThat(policy.choose(assessment, ModelTier.HAIKU, 1000, emptyList(), workload, adjustments = pessimistic).option.key).isNotEqualTo("$haiku/low")
        assertThat(RoutingAdjustment(haiku, "low", successOffset = -5.0).normalized().successOffset).isEqualTo(-0.3)
    }

    @Test
    fun `웹에서 조정하면 저장되고 다음 배정의 실행 기록에 버전이 남으며 이전 버전으로 되돌린다`() {
        val saved = mutableListOf<RoutingAdjustments>()
        val store = object : RoutingAdjustmentStore {
            override fun loadAll() = saved.toList()
            override fun save(adjustments: RoutingAdjustments) { saved += adjustments }
        }
        val selector = ModelSelector(NoAssessor, ModelTier.SONNET)
        val service = TaskService(selector, NoStore, MutableClock(), "medium", adjustmentStore = store)
        val mvc = MockMvcBuilders.standaloneSetup(RoutingController(service)).build()

        mvc.perform(put("/api/routing/adjustments").contentType(MediaType.APPLICATION_JSON)
            .content("""{"items":[{"model":"$haiku","effort":"low","enabled":false},{"model":"$sonnet","effort":"medium"}],"note":"haiku low 끔"}"""))
            .andExpect(jsonPath("$.items.length()").value(1))
        val version = selector.adjustments.version
        assertThat(saved).hasSize(1)

        service.poll("r1", listOf(Project("demo", "demo", null)))
        val task = service.create("demo", "slug", "기능")
        service.poll("r1", listOf(Project("demo", "demo", null)))
        assertThat(service.latest(task).current()!!.executions.single().adjustmentVersion).isEqualTo(version)

        mvc.perform(put("/api/routing/adjustments").contentType(MediaType.APPLICATION_JSON).content("""{"items":[]}"""))
        assertThat(selector.adjustments.items).isEmpty()
        mvc.perform(post("/api/routing/adjustments/$version/restore")).andExpect(status().isOk)
        assertThat(selector.adjustments.version).isEqualTo(version)
        mvc.perform(get("/api/routing")).andExpect(jsonPath("$.history.length()").value(3)).andExpect(jsonPath("$.current.version").value(version))

        val restarted = ModelSelector(NoAssessor, ModelTier.SONNET)
        TaskService(restarted, NoStore, MutableClock(), "medium", adjustmentStore = store).restore()
        assertThat(restarted.adjustments.version).isEqualTo(version)
    }
}
