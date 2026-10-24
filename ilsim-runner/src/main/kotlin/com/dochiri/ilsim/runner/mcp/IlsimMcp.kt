package com.dochiri.ilsim.runner.mcp

import com.dochiri.ilsim.runner.AgentTaskRequest
import com.dochiri.ilsim.runner.AgentTaskServer
import com.dochiri.ilsim.runner.AiUsage
import com.dochiri.ilsim.runner.AssignedTask
import com.dochiri.ilsim.runner.ConfigStore
import com.dochiri.ilsim.runner.DecisionScope
import com.dochiri.ilsim.runner.Gate
import com.dochiri.ilsim.runner.PollRequest
import com.dochiri.ilsim.runner.ProcessingDecision
import com.dochiri.ilsim.runner.ProjectReport
import com.dochiri.ilsim.runner.ReviewCache
import com.dochiri.ilsim.runner.ReviewContext
import com.dochiri.ilsim.runner.ReviewRouter
import com.dochiri.ilsim.runner.Shell
import com.dochiri.ilsim.runner.StageGates
import com.dochiri.ilsim.runner.StageResult
import com.dochiri.ilsim.runner.UsageScope
import com.dochiri.ilsim.runner.analyzeArchitecture
import com.dochiri.ilsim.runner.analyzeConvention
import com.dochiri.ilsim.runner.architecture.ArchitectureStore
import com.dochiri.ilsim.runner.brief.RulesBrief
import com.dochiri.ilsim.runner.convention.ConventionStore
import com.dochiri.ilsim.runner.jev.Jev
import com.dochiri.ilsim.runner.jev.RequirementCheck
import com.dochiri.ilsim.runner.risk.RiskScanner
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class IlsimMcp(
    private val store: ConfigStore,
    private val server: AgentTaskServer,
    private val active: ActiveTasks,
    private val cwd: Path,
    private val ask: (prompt: String, model: String) -> String = { prompt, model -> ReviewRouter.askWithCost(prompt, model).text },
    private val jev: () -> Jev = Jev::fromEnvironment,
) {
    fun tools(): List<McpTool> = listOf(
        McpTool("start_task", "코드·문서를 바꾸는 요청을 일심 작업으로 시작한다. 작업 브랜치로 전환하고 첫 단계 지시를 돌려준다.", schema(
            """{"type":"object","properties":{"requirement":{"type":"string","description":"사용자 요구사항 원문"},"feature":{"type":"string","description":"기능 이름(영문 소문자·숫자·하이픈, 생략 가능)"}},"required":["requirement"]}""",
        )) { args -> startTask(args.text("requirement") ?: throw IllegalArgumentException("requirement 가 필요합니다"), args.text("feature")) },
        McpTool("next_step", "진행 중인 일심 작업의 상태와 현재 단계 지시를 돌려준다. 작업이 없으면 그 사실을 알린다.", schema(
            """{"type":"object","properties":{}}""",
        )) { nextStep() },
        McpTool("submit_result", "현재 단계 결과를 제출한다. 일심이 검증하고 통과하면 커밋한 뒤 서버에 보고한다. 실패하면 고칠 점을 돌려준다.", schema(
            """{"type":"object","properties":{"summary":{"type":"string","description":"이 단계에서 한 일 요약"}}}""",
        )) { args -> submit(args.text("summary")) },
        McpTool("check_change", "제출 전에 현재 변경을 빠르게 검사한다(허용 경로·아키텍처·컨벤션·위험·테스트 무결성). 테스트·변이·독립 검토는 submit_result에서 한다. 차단하지 않는다.", schema(
            """{"type":"object","properties":{}}""",
        )) { checkChange() },
        McpTool("get_rules", "이 프로젝트에서 승인된 아키텍처·컨벤션·위험 규칙을 돌려준다.", schema(
            """{"type":"object","properties":{}}""",
        )) { rules() },
        McpTool("analyze_project", "프로젝트 코드에서 아키텍처·컨벤션 규칙을 추출하고 AI 검토로 확실한 규칙을 자동 승인한다. 나머지는 웹 규칙 화면에서 승인한다. 몇 분 걸릴 수 있다.", schema(
            """{"type":"object","properties":{}}""",
        )) { analyze() },
        McpTool("retry_task", "차단된(BLOCKED) 일심 작업을 다시 시도한다. 사용자가 지시를 주면 instruction으로 넘긴다.", schema(
            """{"type":"object","properties":{"instruction":{"type":"string","description":"다시 시도할 때 반영할 사용자 지시(생략 가능)"}}}""",
        )) { args -> retry(args.text("instruction")) },
        McpTool("approve_stage", "사용자가 승인한 단계 결과를 승인한다. 사용자가 채팅에서 승인한다고 말했을 때만 호출한다. 호출하면 앱이 사용자에게 다시 확인받는다.", schema(
            """{"type":"object","properties":{"stage":{"type":"string","description":"승인할 단계(REQUIREMENTS 또는 TEST_CASES)"}},"required":["stage"]}""",
        )) { args -> approve(args.text("stage") ?: throw IllegalArgumentException("stage 가 필요합니다")) },
        McpTool("reject_stage", "사용자가 고칠 점을 말한 단계 결과를 거절하고, 그 사유로 같은 단계를 다시 시작한다. 호출하면 앱이 사용자에게 다시 확인받는다.", schema(
            """{"type":"object","properties":{"stage":{"type":"string","description":"거절할 단계"},"feedback":{"type":"string","description":"사용자가 말한 수정 사항"}},"required":["stage","feedback"]}""",
        )) { args -> reject(args.text("stage") ?: throw IllegalArgumentException("stage 가 필요합니다"), args.text("feedback") ?: throw IllegalArgumentException("feedback 이 필요합니다")) },
    )

    private fun workspace(): Workspace {
        Workspace.of(store.load(), cwd)?.let { return it }
        val root = Workspace.root(cwd) ?: throw IllegalStateException("Git 저장소가 아닙니다: $cwd")
        throw IllegalStateException("일심에 등록되지 않은 프로젝트입니다: $root. 터미널에서 `ilsim-runner add <id> $root`로 등록하세요")
    }

    private fun startTask(requirement: String, feature: String?): String {
        val ws = workspace()
        active.load(ws.root)?.let { current ->
            if (server.agentTask(current.taskId)?.status == "ACTIVE") {
                throw IllegalStateException("이미 진행 중인 작업이 있습니다: ${current.feature} (${current.taskId.take(8)}). next_step으로 이어서 하세요")
            }
            active.clear(ws.root)
        }
        val dirty = ws.changedFiles()
        if (dirty.isNotEmpty()) throw IllegalStateException("커밋하지 않은 변경이 있어 작업을 시작할 수 없습니다(${dirty.take(5).joinToString()}). 사용자에게 커밋하거나 되돌릴지 물어보세요")
        val config = store.load()
        server.register(PollRequest(config.runnerId, listOf(ProjectReport(ws.projectId, ws.project.name))))
        val created = server.createAgentTask(AgentTaskRequest(ws.projectId, feature?.takeIf { it.isNotBlank() }, requirement))
        val branch = "ilsim/${created.feature}-${created.taskId.take(8)}"
        Shell.gitOk(ws.root, "checkout", "-b", branch)
        active.save(ws.root, ActiveTask(created.taskId, ws.projectId, created.feature, branch))
        val warning = ws.installCommitHook(runnerCommand)?.let { "\n주의: $it" }.orEmpty()
        return "작업을 만들었습니다: ${created.feature} (${created.taskId.take(8)}), 브랜치 $branch$warning\n\n" + nextStep()
    }

    private fun nextStep(): String {
        val ws = workspace()
        val task = active.load(ws.root) ?: return "진행 중인 일심 작업이 없습니다(프로젝트 ${ws.projectId}). 코드·문서를 바꾸는 요청이면 start_task로 시작하세요"
        val assigned = server.claim(task.taskId, store.load().runnerId) ?: return describe(ws, task)
        active.save(ws.root, task.copy(stage = assigned.stage, status = RUNNING, stopBlocks = 0))
        val prompt = gates {}.promptWithRules(assigned.copy(testCommand = ws.project.testCommand))
        return listOf("## 일심 단계: ${assigned.stage} (시도 ${assigned.attempt})", prompt, rules(assigned.stage, task.feature)).joinToString("\n\n")
    }

    private fun describe(ws: Workspace, task: ActiveTask): String {
        val state = server.agentTask(task.taskId) ?: run {
            active.clear(ws.root)
            return "서버에서 작업을 찾을 수 없어 활성 작업을 지웠습니다"
        }
        return when {
            state.status == "COMPLETED" -> {
                active.clear(ws.root)
                "작업이 끝났습니다: ${task.feature}. 브랜치 ${task.branch}에 단계별 커밋이 있습니다. 병합 여부는 사용자가 정합니다"
            }
            state.status == "BLOCKED" -> {
                active.save(ws.root, task.copy(stage = state.stage, status = "BLOCKED"))
                "작업이 차단됐습니다(${state.stage}): ${state.error ?: "사유 없음"}. 사용자에게 사유를 알리고, 다시 시도하자고 하면 retry_task(사용자 지시)를 호출하세요"
            }
            state.stageStatus == "WAITING_APPROVAL" -> {
                active.save(ws.root, task.copy(stage = state.stage, status = "WAITING_APPROVAL"))
                "${state.stage} 단계 결과가 사용자 승인을 기다립니다. 산출물(${artifact(state.stage, task.feature)})을 사용자에게 요약해 보여 주고 승인 여부를 물어보세요. 사용자가 승인하면 approve_stage, 고칠 점을 말하면 reject_stage(사유)를 호출합니다. 사용자가 답하기 전에는 호출하지 마세요"
            }
            else -> "단계를 시작하지 못했습니다(작업 ${state.status}, 단계 ${state.stage} ${state.stageStatus})"
        }
    }

    private fun checkChange(): String {
        val ws = workspace()
        val task = active.load(ws.root) ?: throw IllegalStateException("진행 중인 일심 작업이 없습니다")
        val stage = task.stage?.takeIf { task.status == RUNNING } ?: throw IllegalStateException("실행 중인 단계가 없습니다(상태 ${task.status ?: "-"}). next_step을 호출하세요")
        val notes = mutableListOf<String>()
        val validation = linkedMapOf<String, String>()
        val assigned = AssignedTask(task.taskId, ws.projectId, stage, 1, task.feature, "claude-app", prompt = "", testCommand = ws.project.testCommand)
        val changed = ws.changedFiles()
        val problems = Gate.pathViolations(stage, task.feature, changed).filterNot { changed.isEmpty() } + gates(notes::add).quickCheck(assigned, ws.root, validation)
        return buildString {
            appendLine("변경 파일 ${changed.size}개, 검사: ${validation.entries.joinToString(", ") { "${it.key}=${it.value}" }.ifEmpty { "없음" }}")
            if (problems.isEmpty()) appendLine("문제 없음. 테스트·변이·독립 검토는 submit_result에서 실행합니다")
            else { appendLine("고칠 것:"); problems.forEach { appendLine("- $it") } }
            notes.filter { it.startsWith("위험 후보") }.takeIf { it.isNotEmpty() }?.let { candidates ->
                appendLine("참고(차단하지 않음):")
                candidates.forEach { appendLine("- $it") }
            }
        }.trimEnd()
    }

    private fun rules(): String {
        val ws = workspace()
        return RulesBrief.build(ArchitectureStore(store).load(ws.projectId), ConventionStore(store).load(ws.projectId), RiskScanner.Store(store).load(ws.projectId))
            ?: "승인된 규칙이 없습니다. analyze_project로 분석한 뒤 웹 규칙 화면에서 승인하세요"
    }

    private fun analyze(): String {
        val ws = workspace()
        val out = StringBuilder()
        for ((label, run) in listOf<Pair<String, (ConfigStore, String, Appendable) -> Unit>>("아키텍처" to ::analyzeArchitecture, "컨벤션" to ::analyzeConvention)) {
            out.appendLine("[$label]")
            runCatching { run(store, ws.projectId, out) }.onFailure { out.appendLine("$label 분석 실패: ${it.message}") }
            out.appendLine()
        }
        return out.toString().trimEnd().let { if (it.length > MAX_OUTPUT) it.take(MAX_OUTPUT) + "\n…(생략)" else it }
    }

    private fun retry(instruction: String?): String {
        val ws = workspace()
        val task = active.load(ws.root) ?: throw IllegalStateException("진행 중인 일심 작업이 없습니다")
        val state = server.agentTask(task.taskId) ?: throw IllegalStateException("서버에서 작업을 찾을 수 없습니다")
        if (state.status != "BLOCKED") throw IllegalStateException("차단된 작업이 아닙니다(상태 ${state.status})")
        server.retry(task.taskId, instruction)
        return "다시 시도합니다\n\n" + nextStep()
    }

    private fun waiting(stage: String): Pair<Workspace, ActiveTask> {
        val ws = workspace()
        val task = active.load(ws.root) ?: throw IllegalStateException("진행 중인 일심 작업이 없습니다")
        val state = server.agentTask(task.taskId) ?: throw IllegalStateException("서버에서 작업을 찾을 수 없습니다")
        if (state.stageStatus != "WAITING_APPROVAL" || state.stage != stage) {
            throw IllegalStateException("$stage 단계가 승인 대기 중이 아닙니다(현재 ${state.stage} ${state.stageStatus})")
        }
        return ws to task
    }

    private fun approve(stage: String): String {
        val (_, task) = waiting(stage)
        server.approve(task.taskId)
        return "$stage 단계를 승인했습니다\n\n" + nextStep()
    }

    private fun reject(stage: String, feedback: String): String {
        val (_, task) = waiting(stage)
        server.reject(task.taskId, feedback)
        return "$stage 단계를 거절했습니다. 사유를 반영해 같은 단계를 다시 합니다\n\n" + nextStep()
    }

    private fun artifact(stage: String?, feature: String): String = when (stage) {
        "REQUIREMENTS" -> Gate.requirementsPath(feature)
        "TEST_CASES" -> Gate.testSpecPath(feature)
        else -> "변경 diff"
    }

    private fun submit(summary: String?): String {
        val ws = workspace()
        val task = active.load(ws.root) ?: throw IllegalStateException("진행 중인 일심 작업이 없습니다. start_task로 시작하세요")
        val branch = ws.branch()
        if (branch != task.branch) throw IllegalStateException("작업 브랜치(${task.branch})가 아닙니다(현재 $branch). 브랜치를 바꾸지 마세요")
        val assigned = server.claim(task.taskId, store.load().runnerId) ?: return describe(ws, task)
        val stage = assigned.stage
        fun log(message: String) {
            System.err.println("[ilsim] $message")
            runCatching { server.event(task.taskId, stage, "log", message) }
        }
        summary?.takeIf { it.isNotBlank() }?.let { runCatching { server.event(task.taskId, stage, "text", it) } }
        val usage = mutableListOf<AiUsage>()
        val decisions = mutableListOf<ProcessingDecision>()
        val validation = linkedMapOf<String, String>()
        val outcome = UsageScope.collect(usage, {}, assigned.executionId) {
            ReviewContext.within(assigned.reviewPolicy, stage) {
                DecisionScope.collect(decisions) { verify(ws, task, assigned.copy(testCommand = ws.project.testCommand), validation, ::log) }
            }
        }
        if (outcome.error != null) {
            Shell.git(ws.root, "reset", "-q")
            log("제출 실패: ${outcome.error.lines().first()}")
            return "제출 실패($stage):\n${outcome.error}\n\n지적된 부분을 고친 뒤 submit_result를 다시 호출하세요"
        }
        Shell.gitOk(ws.root, "-c", "user.name=ilsim", "-c", "user.email=ilsim@localhost", "commit", "-m", "ilsim(${stage.lowercase()}): ${task.feature}")
        server.result(task.taskId, StageResult(true, diff = outcome.diff, artifacts = outcome.artifacts, validation = validation, branch = task.branch,
            model = assigned.model, executionId = assigned.executionId, stage = stage, usage = usage.toList(), decisions = decisions.toList()))
        active.save(ws.root, task.copy(stage = stage, status = "SUBMITTED", stopBlocks = 0))
        val checks = validation.entries.joinToString(", ") { "${it.key}=${it.value}" }.ifEmpty { "형식·경로 검사" }
        return "통과($stage): $checks. 커밋했습니다\n\n" + nextStep()
    }

    private class Outcome(val error: String? = null, val diff: String? = null, val artifacts: Map<String, String> = emptyMap())

    private fun verify(ws: Workspace, task: ActiveTask, assigned: AssignedTask, validation: MutableMap<String, String>, log: (String) -> Unit): Outcome {
        val stage = assigned.stage
        val violations = Gate.pathViolations(stage, task.feature, ws.changedFiles())
        if (violations.isNotEmpty()) return Outcome(violations.joinToString("\n"))
        val required = when (stage) {
            "REQUIREMENTS" -> Gate.requirementsPath(task.feature)
            "TEST_CASES" -> Gate.testSpecPath(task.feature)
            else -> null
        }
        if (required != null && !ws.root.resolve(required).exists()) return Outcome("산출물이 없습니다: $required")
        val gates = gates(log)
        gates.failure(assigned, ws.project, ws.root, validation)?.let { return Outcome(it) }
        val artifacts = required?.let { mapOf(it to ws.root.resolve(it).readText()) }.orEmpty()
        Shell.gitOk(ws.root, "add", "-A")
        val diff = Shell.gitOk(ws.root, "diff", "--cached", "HEAD")
        val check = if (stage == "IMPLEMENTATION") requirementCheck(ws, task, diff, validation) else null
        if (check != null && check.missing.isNotEmpty()) {
            return Outcome("구현에서 요구사항이 빠진 것으로 보입니다. 아래 요구사항을 구현한 뒤 다시 제출하세요:\n" +
                check.missing.joinToString("\n") { "- ${it.id} ${it.text} (충족 확률 ${"%.2f".format(check.probabilities[it.id])})" })
        }
        val audit = if (auditNeeded(ws, stage, check, gates.riskCandidates, diff, assigned.executionId.orEmpty(), log)) {
            gates.aiAudit(assigned, ws.project, ws.root, diff, validation, ReviewCache.digest(assigned.prompt + ReviewRouter.policyKey))
        } else {
            validation["ai-audit"] = "SKIPPED"
            StageGates.AuditOutcome(emptyMap(), emptyList())
        }
        if (audit.fixable.isNotEmpty()) {
            return Outcome("AI 변경 검토가 아래를 지적했습니다. 해당 부분만 고치세요:\n" + audit.fixable.joinToString("\n") { "- [${it.type}] ${it.target} ${it.reason}" })
        }
        return Outcome(diff = diff, artifacts = artifacts + audit.artifacts)
    }

    private fun requirementCheck(ws: Workspace, task: ActiveTask, diff: String, validation: MutableMap<String, String>): RequirementCheck.Result? {
        val spec = ws.root.resolve(Gate.requirementsPath(task.feature)).takeIf { it.exists() }?.readText()
        val requirements = spec?.let(RequirementCheck::parse).orEmpty()
        val result = runCatching { RequirementCheck.judge(jev(), requirements, diff) }.getOrNull()
        validation["requirements"] = when {
            result == null -> "UNKNOWN"
            result.missing.isNotEmpty() -> "MISSING"
            result.uncertain.isNotEmpty() -> "UNCERTAIN"
            else -> "PASSED"
        }
        DecisionScope.record("REQUIREMENT_CHECK", if (result == null) "NONE" else "JEV",
            result?.let { r -> r.probabilities.entries.joinToString(", ") { "${it.key}=${"%.2f".format(it.value)}" } + if (r.truncated) " (diff 일부만 판정)" else "" }
                ?: "JEV 꺼짐·실패 또는 요구사항 ID 없음", diff)
        return result
    }

    private fun auditNeeded(ws: Workspace, stage: String, check: RequirementCheck.Result?, riskCandidates: Int, diff: String, executionId: String, log: (String) -> Unit): Boolean {
        if (stage == "TEST_CODE") return true
        if (stage != "IMPLEMENTATION") return false
        val maxLines = ws.project.auditMaxDiffLines ?: AUDIT_MAX_DIFF_LINES
        val lines = diff.lines().count { (it.startsWith("+") || it.startsWith("-")) && !it.startsWith("+++") && !it.startsWith("---") }
        val reasons = listOfNotNull(
            "요구사항 판정 없음".takeIf { check == null },
            "판정이 불확실한 요구사항 ${check?.uncertain?.joinToString { it.id }}".takeIf { check != null && check.uncertain.isNotEmpty() },
            "위험 후보 ${riskCandidates}건".takeIf { riskCandidates > 0 },
            "변경 ${lines}줄(기준 ${maxLines}줄 초과)".takeIf { lines > maxLines },
        )
        if (reasons.isNotEmpty()) {
            DecisionScope.record("CHANGE_AUDIT", "AGENT", "조건부 검토: " + reasons.joinToString(", "), diff)
            return true
        }
        val rate = (ws.project.auditSampleRate ?: AUDIT_SAMPLE_RATE).coerceIn(0.0, 1.0)
        val sampled = Math.floorMod(executionId.hashCode(), 100) < (rate * 100).toInt()
        DecisionScope.record("CHANGE_AUDIT", if (sampled) "SAMPLE" else "SKIP",
            if (sampled) "건너뛸 조건이지만 표본(${(rate * 100).toInt()}%)으로 검토" else "요구사항 판정 충족·위험 후보 없음·변경 ${lines}줄로 검토 생략", diff)
        log(if (sampled) "독립 검토: 표본으로 실행" else "독립 검토: 조건 충족으로 생략")
        return sampled
    }

    private fun gates(log: (String) -> Unit): StageGates =
        StageGates(store, ArchitectureStore(store), ConventionStore(store), ask, log)

    private fun rules(stage: String, feature: String): String = """
        ## 일심 진행 규칙
        - 이 단계에서 고칠 수 있는 경로: ${allowed(stage, feature)}
        - git commit·push·브랜치 변경은 하지 않는다. 커밋은 일심이 한다.
        - 단계를 마치면 submit_result를 호출한다. 실패하면 지적된 부분만 고치고 다시 호출한다.
        - 사용자에게 보고할 때 submit_result 결과를 함께 알린다.
        """.trimIndent()

    companion object {
        const val RUNNING = "RUNNING"

        private const val MAX_OUTPUT = 12_000

        const val AUDIT_SAMPLE_RATE = 0.2

        const val AUDIT_MAX_DIFF_LINES = 300

        private val runnerCommand: String get() = System.getenv("ILSIM_RUNNER_BIN")?.takeIf { it.isNotBlank() } ?: "ilsim-runner"

        const val INSTRUCTIONS = "일심이 관리하는 저장소에서는 코드·문서를 바꾸기 전에 start_task(새 작업) 또는 next_step(진행 중 작업)을 호출하고, 단계를 마치면 submit_result로 제출한다. 질문·설명 요청이면 작업을 만들지 않는다."

        fun allowed(stage: String?, feature: String): String = when (stage) {
            "REQUIREMENTS" -> Gate.requirementsPath(feature)
            "TEST_CASES" -> Gate.testSpecPath(feature)
            "TEST_CODE" -> "테스트 경로(src/test 등)만"
            "IMPLEMENTATION" -> "테스트와 specs/ 를 뺀 프로덕션 코드"
            else -> "없음"
        }

        private fun schema(text: String): JsonObject = kotlinx.serialization.json.Json.parseToJsonElement(text) as JsonObject

        private fun JsonObject.text(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }
}
