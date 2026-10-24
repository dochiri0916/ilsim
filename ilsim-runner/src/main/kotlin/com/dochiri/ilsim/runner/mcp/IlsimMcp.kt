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
import com.dochiri.ilsim.runner.architecture.ArchitectureStore
import com.dochiri.ilsim.runner.convention.ConventionStore
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
                throw IllegalStateException("이미 진행 중인 작업이 있습니다: ${current.feature} (${current.taskId.take(8)}). next_step으로 이어서 하거나 웹에서 중지하세요")
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
        return "작업을 만들었습니다: ${created.feature} (${created.taskId.take(8)}), 브랜치 $branch\n\n" + nextStep()
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
                "작업이 차단됐습니다(${state.stage}): ${state.error ?: "사유 없음"}. 사용자가 웹에서 재시도하거나 중지해야 합니다"
            }
            state.stageStatus == "WAITING_APPROVAL" -> {
                active.save(ws.root, task.copy(stage = state.stage, status = "WAITING_APPROVAL"))
                "${state.stage} 단계 결과가 사용자 승인을 기다립니다. 산출물(${artifact(state.stage, task.feature)})을 사용자에게 요약해 보여 주고 승인 여부를 물어보세요. 사용자가 승인하면 approve_stage, 고칠 점을 말하면 reject_stage(사유)를 호출합니다. 사용자가 답하기 전에는 호출하지 마세요"
            }
            else -> "단계를 시작하지 못했습니다(작업 ${state.status}, 단계 ${state.stage} ${state.stageStatus})"
        }
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
        val audit = gates.aiAudit(assigned, ws.project, ws.root, diff, validation, ReviewCache.digest(assigned.prompt + ReviewRouter.policyKey))
        if (audit.fixable.isNotEmpty()) {
            return Outcome("AI 변경 검토가 아래를 지적했습니다. 해당 부분만 고치세요:\n" + audit.fixable.joinToString("\n") { "- [${it.type}] ${it.target} ${it.reason}" })
        }
        return Outcome(diff = diff, artifacts = artifacts + audit.artifacts)
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
