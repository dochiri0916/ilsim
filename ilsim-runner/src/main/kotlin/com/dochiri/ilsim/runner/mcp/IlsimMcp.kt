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
import com.dochiri.ilsim.runner.decideRules
import com.dochiri.ilsim.runner.rulesReport
import com.dochiri.ilsim.runner.architecture.ArchitectureStore
import com.dochiri.ilsim.runner.brief.RulesBrief
import com.dochiri.ilsim.runner.convention.ConventionStore
import com.dochiri.ilsim.runner.document.ProjectDocs
import com.dochiri.ilsim.runner.jev.Guidelines
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
    private val guides: TaskGuides = TaskGuides(store.location.parent.resolve("guides")),
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
        McpTool("get_guidelines", "진행 중인 일심 작업에 적용되는 이 프로젝트 문서 지침(원문·출처·관련도)을 돌려준다. next_step에 넣은 것과 같다.", schema(
            """{"type":"object","properties":{}}""",
        )) { guidelines() },
        McpTool("get_rules", "이 프로젝트에서 승인된 아키텍처·컨벤션·위험 규칙을 돌려준다.", schema(
            """{"type":"object","properties":{}}""",
        )) { rules() },
        McpTool("analyze_project", "프로젝트 코드에서 아키텍처·컨벤션 규칙을 추출하고 AI 검토로 확실한 규칙을 자동 승인한다. 프로젝트 문서에 적힌 규칙 중 기계적으로 확인되는 것은 정적 규칙(doc:)으로 옮긴다. 나머지는 review_rules로 보여 주고 사용자가 정하면 decide_rules로 반영한다. 몇 분 걸릴 수 있다.", schema(
            """{"type":"object","properties":{}}""",
        )) { analyze() },
        McpTool("review_rules", "추출된 아키텍처·컨벤션 규칙과 학습 메모리 후보의 상태(승인·거절·후보)와 근거를 돌려준다. 사용자가 규칙을 검토할 때 쓴다.", schema(
            """{"type":"object","properties":{}}""",
        )) { rulesReport(store, workspace().projectId) },
        McpTool("decide_rules", "사용자가 정한 대로 규칙·학습 메모리 후보를 승인하거나 거절한다. 사용자가 말했을 때만 호출하고, 호출하면 앱이 사용자에게 다시 확인받는다.", schema(
            """{"type":"object","properties":{"ids":{"type":"array","items":{"type":"string"},"description":"규칙·메모리 id 목록"},"decision":{"type":"string","enum":["approve","reject"]}},"required":["ids","decision"]}""",
        )) { args ->
            val ids = (args["ids"] as? kotlinx.serialization.json.JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
            require(ids.isNotEmpty()) { "ids 가 필요합니다" }
            val decision = args.text("decision") ?: throw IllegalArgumentException("decision 이 필요합니다")
            require(decision in setOf("approve", "reject")) { "decision 은 approve 또는 reject 입니다" }
            decideRules(store, workspace().projectId, ids, decision == "approve")
        },
        McpTool("retry_task", "차단된(BLOCKED) 일심 작업을 다시 시도한다. 사용자가 지시를 주면 instruction으로 넘긴다.", schema(
            """{"type":"object","properties":{"instruction":{"type":"string","description":"다시 시도할 때 반영할 사용자 지시(생략 가능)"}}}""",
        )) { args -> retry(args.text("instruction")) },
        McpTool("approve_stage", "사용자가 승인한 단계 결과를 승인한다. 사용자가 채팅에서 승인한다고 말했을 때만 호출한다. 호출하면 앱이 사용자에게 다시 확인받는다.", schema(
            """{"type":"object","properties":{"stage":{"type":"string","description":"승인할 단계(REQUIREMENTS 또는 TEST_CASES)"}},"required":["stage"]}""",
        )) { args -> approve(args.text("stage") ?: throw IllegalArgumentException("stage 가 필요합니다")) },
        McpTool("reject_stage", "사용자가 고칠 점을 말한 단계 결과를 거절하고, 그 사유로 같은 단계를 다시 시작한다. 호출하면 앱이 사용자에게 다시 확인받는다.", schema(
            """{"type":"object","properties":{"stage":{"type":"string","description":"거절할 단계"},"feedback":{"type":"string","description":"사용자가 말한 수정 사항"}},"required":["stage","feedback"]}""",
        )) { args -> reject(args.text("stage") ?: throw IllegalArgumentException("stage 가 필요합니다"), args.text("feedback") ?: throw IllegalArgumentException("feedback 이 필요합니다")) },
        McpTool("reopen_stage", "이미 통과한 코드 단계(TEST_CODE)로 되돌려 그 단계부터 다시 한다. 앞 단계 산출물(예: 테스트 기대값)이 틀려 현재 단계를 통과할 수 없을 때, 사용자가 되돌리자고 했을 때만 호출한다. 현재 단계의 미커밋 변경은 보관했다가 그 단계로 돌아오면 되살린다. 호출하면 앱이 사용자에게 다시 확인받는다.", schema(
            """{"type":"object","properties":{"stage":{"type":"string","description":"되돌릴 단계(TEST_CODE)"},"feedback":{"type":"string","description":"그 단계에서 고칠 점"}},"required":["stage","feedback"]}""",
        )) { args -> reopen(args.text("stage") ?: throw IllegalArgumentException("stage 가 필요합니다"), args.text("feedback") ?: throw IllegalArgumentException("feedback 이 필요합니다")) },
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
        guides.save(created.taskId, buildGuide(ws, created.feature, requirement))
        val warning = ws.installCommitHook(runnerCommand)?.let { "\n주의: $it" }.orEmpty()
        return "작업을 만들었습니다: ${created.feature} (${created.taskId.take(8)}), 브랜치 $branch$warning\n\n" + nextStep()
    }

    private fun nextStep(): String {
        val ws = workspace()
        val task = active.load(ws.root) ?: return "진행 중인 일심 작업이 없습니다(프로젝트 ${ws.projectId}). 코드·문서를 바꾸는 요청이면 start_task로 시작하세요"
        val assigned = server.claim(task.taskId, store.load().runnerId) ?: return describe(ws, task)
        val restored = if (task.shelved != null && task.shelvedStage == assigned.stage) unshelve(ws, task.shelved) else null
        active.save(ws.root, task.copy(stage = assigned.stage, status = RUNNING, stopBlocks = 0, attention = emptyList(),
            shelved = task.shelved.takeIf { restored == null || restored.startsWith(UNSHELVE_FAILED) }, shelvedStage = task.shelvedStage.takeIf { restored == null || restored.startsWith(UNSHELVE_FAILED) }))
        val prompt = gates {}.promptWithRules(assigned.copy(testCommand = ws.project.testCommand))
        return listOfNotNull("## 일심 단계: ${assigned.stage} (시도 ${assigned.attempt})", restored, prompt, guide(ws, task, assigned.prompt).render(), rules(assigned.stage, task.feature)).joinToString("\n\n")
    }

    private fun describe(ws: Workspace, task: ActiveTask): String {
        val state = server.agentTask(task.taskId) ?: run {
            active.clear(ws.root)
            return "서버에서 작업을 찾을 수 없어 활성 작업을 지웠습니다"
        }
        return when {
            state.status == "COMPLETED" -> {
                active.clear(ws.root)
                guides.clear(task.taskId)
                "작업이 끝났습니다: ${task.feature}. 브랜치 ${task.branch}에 단계별 커밋이 있습니다. 병합 여부는 사용자가 정합니다"
            }
            state.status == "BLOCKED" -> {
                active.save(ws.root, task.copy(stage = state.stage, status = "BLOCKED"))
                "작업이 차단됐습니다(${state.stage}): ${state.error ?: "사유 없음"}. 사용자에게 사유를 알리고, 다시 시도하자고 하면 retry_task(사용자 지시)를 호출하세요"
            }
            state.stageStatus == "WAITING_APPROVAL" -> {
                active.save(ws.root, task.copy(stage = state.stage, status = "WAITING_APPROVAL"))
                "${state.stage} 단계 결과가 사용자 승인을 기다립니다. 산출물(${artifact(state.stage, task.feature)})을 사용자에게 요약해 보여 주고 승인 여부를 물어보세요. 사용자가 승인하면 approve_stage, 고칠 점을 말하면 reject_stage(사유)를 호출합니다. 사용자가 답하기 전에는 호출하지 마세요" +
                    task.attention.takeIf { it.isNotEmpty() }?.let { items ->
                        "\n\n승인을 묻기 전에 사용자에게 반드시 알리세요. 일심이 아래 문서 지침의 준수를 확인하지 못했습니다. 사용자가 산출물이 이 지침을 지키는지 직접 확인한 뒤 승인하도록 안내하세요:\n" + items.joinToString("\n") { "- $it" }
                    }.orEmpty()
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

    private fun guidelines(): String {
        val ws = workspace()
        val task = active.load(ws.root) ?: throw IllegalStateException("진행 중인 일심 작업이 없습니다")
        return guide(ws, task, task.feature).render()
    }

    private fun guide(ws: Workspace, task: ActiveTask, request: String): TaskGuide =
        guides.load(task.taskId)?.takeIf { it.projectId == ws.projectId && (it.selected || !jev().enabled) }
            ?: buildGuide(ws, task.feature, request).also { guides.save(task.taskId, it) }

    private fun buildGuide(ws: Workspace, feature: String, request: String): TaskGuide {
        val others = store.load().projects.filterKeys { it != ws.projectId }.values.map { Path.of(it.path) }
        val files = ProjectDocs.files(ws.root, ws.project, others, feature)
        val sections = ProjectDocs.sections(files)
        val scored = if (sections.isEmpty()) emptyList() else runCatching { Guidelines.select(jev(), request, sections, ProjectDocs.modules(ws.root)) }.getOrNull()
        return TaskGuide(ws.projectId, scored != null, sections.size, scored?.let(Guidelines::relevant).orEmpty(),
            if (scored == null) files.keys.toList() else emptyList(), scored?.let(Guidelines::scores).orEmpty())
    }

    private fun rules(): String {
        val ws = workspace()
        return RulesBrief.build(ArchitectureStore(store).load(ws.projectId), ConventionStore(store).load(ws.projectId), RiskScanner.Store(store).load(ws.projectId))
            ?: "승인된 규칙이 없습니다. analyze_project로 분석한 뒤 review_rules로 확인하고 decide_rules로 승인하세요"
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

    private fun reopen(stage: String, feedback: String): String {
        val ws = workspace()
        val task = active.load(ws.root) ?: throw IllegalStateException("진행 중인 일심 작업이 없습니다")
        if (ws.branch() != task.branch) throw IllegalStateException("작업 브랜치(${task.branch})가 아닙니다. 브랜치를 바꾸지 마세요")
        val state = server.agentTask(task.taskId) ?: throw IllegalStateException("서버에서 작업을 찾을 수 없습니다")
        if (state.status !in setOf("ACTIVE", "BLOCKED") || state.stage == null) throw IllegalStateException("진행 중인 작업이 아닙니다(상태 ${state.status})")
        val label = "ilsim-reopen-${task.taskId}"
        val shelved = ws.changedFiles().isNotEmpty()
        if (shelved) Shell.gitOk(ws.root, "stash", "push", "--include-untracked", "-m", label)
        try {
            server.reopen(task.taskId, stage, feedback)
        } catch (e: Exception) {
            if (shelved) unshelve(ws, label)
            throw e
        }
        active.save(ws.root, task.copy(stage = stage, status = "PENDING", stopBlocks = 0,
            shelved = if (shelved) label else task.shelved, shelvedStage = if (shelved) state.stage else task.shelvedStage))
        val note = if (shelved) "${state.stage} 단계의 미커밋 변경을 보관했습니다. ${state.stage} 단계로 돌아오면 되살립니다" else "보관할 변경이 없습니다"
        return "$stage 단계로 되돌렸습니다. $note\n\n" + nextStep()
    }

    private fun unshelve(ws: Workspace, label: String): String {
        val ref = Shell.gitOk(ws.root, "stash", "list", "--format=%gd %s").lines().firstOrNull { it.endsWith(label) }?.substringBefore(' ')
            ?: return "$UNSHELVE_FAILED: 보관한 변경($label)을 찾지 못했습니다. 사용자에게 알리세요"
        val pop = Shell.git(ws.root, "stash", "pop", ref)
        return if (pop.exit == 0) "보관했던 이 단계의 변경을 되살렸습니다. 이어서 작업하세요"
        else "$UNSHELVE_FAILED: 보관한 변경($ref)을 되살리다 충돌했습니다. 충돌을 정리하거나 사용자에게 알리세요\n${pop.output.take(1000)}"
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
        active.save(ws.root, task.copy(stage = stage, status = "SUBMITTED", stopBlocks = 0, attention = outcome.attention))
        guides.load(task.taskId)?.takeIf { !it.reported }?.let { guides.save(task.taskId, it.copy(reported = true)) }
        val checks = validation.entries.joinToString(", ") { "${it.key}=${it.value}" }.ifEmpty { "형식·경로 검사" }
        return "통과($stage): $checks. 커밋했습니다\n\n" + nextStep()
    }

    private class Outcome(val error: String? = null, val diff: String? = null, val artifacts: Map<String, String> = emptyMap(), val attention: List<String> = emptyList())

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
        val guide = guide(ws, task, assigned.prompt)
        if (!guide.reported) {
            DecisionScope.record("GUIDELINE_SELECT", if (guide.selected) "JEV" else "NONE",
                if (guide.selected) "문서 구간 ${guide.sections}개 중 ${guide.guidelines.size}개 선택(${Guidelines.IRRELEVANT} 초과): ${guide.scores.joinToString(", ")}" else "JEV 꺼짐으로 지침 선택 못 함, 문서 ${guide.documents.size}개 안내",
                guide.scores.joinToString("\n") + guide.documents.joinToString("\n"))
        }
        val compliance = guidelineCheck(guide, stage, diff, validation)
        if (compliance != null && compliance.violated.isNotEmpty()) {
            return Outcome("이 프로젝트 문서 지침을 어긴 것으로 보입니다. 아래 지침에 맞게 고친 뒤 다시 제출하세요:\n" +
                compliance.violated.joinToString("\n") { g -> "- ${g.source}${g.heading.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()} (위반 확률 ${"%.2f".format(compliance.probabilities[g])})\n  ${g.text.lines().filter { it.isNotBlank() }.take(6).joinToString(" ").take(400)}" })
        }
        val unresolved = when {
            compliance != null -> compliance.uncertain.map { it.source }
            guide.selected && guide.guidelines.isEmpty() -> emptyList()
            else -> listOf("판정 없음")
        }
        val audit = if (auditNeeded(ws, stage, check, unresolved, gates.riskCandidates, diff, assigned.executionId.orEmpty(), log)) {
            gates.aiAudit(assigned, ws.project, ws.root, diff, validation, ReviewCache.digest(assigned.prompt + ReviewRouter.policyKey), guide.render().takeIf { guide.guidelines.isNotEmpty() || !guide.selected })
        } else {
            validation["ai-audit"] = "SKIPPED"
            StageGates.AuditOutcome(emptyMap(), emptyList())
        }
        if (audit.fixable.isNotEmpty()) {
            return Outcome("AI 변경 검토가 아래를 지적했습니다. 해당 부분만 고치세요:\n" + audit.fixable.joinToString("\n") { "- [${it.type}] ${it.target} ${it.reason}" })
        }
        val attention = if (stage !in APPROVAL_STAGES) emptyList() else when {
            compliance != null -> compliance.uncertain.map { g -> "${g.source}${g.heading.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()} (위반 확률 ${compliance.probabilities[g]?.let { "%.2f".format(it) } ?: "-"}${if (compliance.truncated) ", 변경이 커서 일부만 판정" else ""})" }
            guide.selected && guide.guidelines.isEmpty() -> emptyList()
            guide.selected -> listOf("문서 지침 ${guide.guidelines.size}개를 JEV 호출 실패로 판정하지 못했습니다: ${guide.guidelines.take(10).joinToString { it.source }}")
            else -> listOf("JEV를 쓸 수 없어 문서 지침을 고르지도 판정하지도 못했습니다. 이 프로젝트 문서: ${guide.documents.take(10).joinToString()}")
        }
        return Outcome(diff = diff, artifacts = artifacts + audit.artifacts, attention = attention)
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

    private fun guidelineCheck(guide: TaskGuide, stage: String, diff: String, validation: MutableMap<String, String>): Guidelines.Check? {
        if (guide.selected && guide.guidelines.isEmpty()) {
            validation["guidelines"] = "NONE"
            return Guidelines.Check(emptyMap(), emptyList(), emptyList(), false)
        }
        val result = if (guide.selected) runCatching { Guidelines.check(jev(), stage, guide.guidelines, diff) }.getOrNull() else null
        validation["guidelines"] = when {
            result == null -> "UNKNOWN"
            result.violated.isNotEmpty() -> "VIOLATED"
            result.uncertain.isNotEmpty() -> "UNCERTAIN"
            else -> "PASSED"
        }
        DecisionScope.record("GUIDELINE_CHECK", if (result == null) "NONE" else "JEV",
            result?.let { r -> r.probabilities.entries.joinToString(", ") { "${it.key.source}=${"%.2f".format(it.value)}" } + if (r.truncated) " (diff 일부만 판정)" else "" }
                ?: if (guide.selected) "JEV 실패" else "JEV 꺼짐으로 지침 선택 못 함", diff)
        return result
    }

    private fun auditNeeded(ws: Workspace, stage: String, check: RequirementCheck.Result?, unresolvedGuidelines: List<String>, riskCandidates: Int, diff: String, executionId: String, log: (String) -> Unit): Boolean {
        if (stage == "TEST_CODE") return true
        if (stage != "IMPLEMENTATION") return false
        val maxLines = ws.project.auditMaxDiffLines ?: AUDIT_MAX_DIFF_LINES
        val lines = diff.lines().count { (it.startsWith("+") || it.startsWith("-")) && !it.startsWith("+++") && !it.startsWith("---") }
        val reasons = listOfNotNull(
            "요구사항 판정 없음".takeIf { check == null },
            "판정이 불확실한 요구사항 ${check?.uncertain?.joinToString { it.id }}".takeIf { check != null && check.uncertain.isNotEmpty() },
            "문서 지침 판정 불확실 ${unresolvedGuidelines.joinToString()}".takeIf { unresolvedGuidelines.isNotEmpty() },
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
            if (sampled) "건너뛸 조건이지만 표본(${(rate * 100).toInt()}%)으로 검토" else "요구사항·문서 지침 판정 충족·위험 후보 없음·변경 ${lines}줄로 검토 생략", diff)
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
        private val APPROVAL_STAGES = setOf("REQUIREMENTS", "TEST_CASES")
        private const val UNSHELVE_FAILED = "보관 변경 복원 실패"

        private const val MAX_OUTPUT = 12_000

        const val AUDIT_SAMPLE_RATE = 0.2

        const val AUDIT_MAX_DIFF_LINES = 300

        private val runnerCommand: String get() = System.getenv("ILSIM_RUNNER_BIN")?.takeIf { it.isNotBlank() } ?: "ilsim-runner"

        const val INSTRUCTIONS = "일심이 관리하는 저장소에서는 코드·문서를 바꾸기 전에 start_task(새 작업) 또는 next_step(진행 중 작업)을 호출하고, 단계를 마치면 submit_result로 제출한다. 단계 지시의 프로젝트 문서 지침은 불합리해 보여도 그대로 따르고, 따를 수 없으면 사용자에게 묻는다. 질문·설명 요청이면 작업을 만들지 않는다."

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
