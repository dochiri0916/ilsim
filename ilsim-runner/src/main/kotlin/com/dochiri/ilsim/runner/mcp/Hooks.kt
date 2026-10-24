package com.dochiri.ilsim.runner.mcp

import com.dochiri.ilsim.runner.ConfigStore
import com.dochiri.ilsim.runner.Gate
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

object Hooks {
    data class Output(val stdout: String = "", val exit: Int = 0)

    const val MAX_STOP_BLOCKS = 3

    private val json = Json { ignoreUnknownKeys = true }
    private val editTools = setOf("Edit", "Write", "MultiEdit", "NotebookEdit")
    private val decisionTools = listOf("approve_stage", "reject_stage")
    private val gitChange = Regex("""\bgit\s+(?:-C\s+\S+\s+)?(commit|push|checkout|switch|reset|rebase|merge|stash|cherry-pick|revert|restore|branch\s+-[dDmM])\b""")
    private val fileWrite = Regex("""(^|[\s;&|(])(rm|mv|cp|touch|mkdir|tee|truncate|chmod|ln|install)\s|>{1,2}\s*(?!/dev/null|&)\S|\bsed\s+(-\w*\s+)*-i|\bperl\s+-\w*i""")

    fun handle(event: String, input: String, store: ConfigStore, active: ActiveTasks): Output {
        val payload = runCatching { json.parseToJsonElement(input).jsonObject }.getOrNull() ?: return Output()
        val cwd = payload.text("cwd")?.let { Path.of(it) } ?: return Output()
        val ws = Workspace.of(store.load(), cwd) ?: return Output()
        val task = active.load(ws.root)
        return when (event) {
            "user-prompt-submit" -> Output(context(ws, task))
            "pre-tool-use" -> preToolUse(ws, task, payload)
            "stop" -> stop(ws, task, active)
            else -> Output()
        }
    }

    private fun context(ws: Workspace, task: ActiveTask?): String = buildString {
        appendLine("[일심] 이 저장소(${ws.projectId})는 일심이 관리합니다.")
        appendLine("- 코드·문서를 바꾸는 요청이면 먼저 ilsim MCP 도구를 쓰세요: 새 작업은 start_task, 진행 중 작업은 next_step. 단계를 마치면 submit_result로 제출합니다.")
        appendLine("- 질문·설명 요청이면 파일을 바꾸지 말고 답만 하세요.")
        append(if (task == null) "- 진행 중인 일심 작업: 없음" else "- 진행 중인 일심 작업: ${task.feature} (${task.taskId.take(8)}), 단계 ${task.stage ?: "-"}, 상태 ${task.status ?: "-"}")
    }

    private fun preToolUse(ws: Workspace, task: ActiveTask?, payload: JsonObject): Output {
        val tool = payload.text("tool_name") ?: return Output()
        val input = payload["tool_input"] as? JsonObject ?: JsonObject(emptyMap())
        decisionTools.firstOrNull { tool.endsWith("__$it") }?.let { name ->
            val stage = input.text("stage") ?: "-"
            val detail = if (name == "reject_stage") "거절(같은 단계를 다시 작성): ${input.text("feedback") ?: "-"}" else "승인(다음 단계로 진행)"
            return ask("일심 $stage 단계 $detail. 사용자가 직접 확인해야 반영됩니다")
        }
        if (tool == "Bash") {
            val command = input.text("command") ?: return Output()
            if (gitChange.containsMatchIn(command)) return deny("Git 커밋·브랜치·되돌리기는 일심이 맡습니다. 단계를 마쳤으면 submit_result를 호출하세요")
            if (fileWrite.containsMatchIn(command)) blocked(task)?.let { return deny(it) }
            return Output()
        }
        if (tool !in editTools) return Output()
        val target = (input.text("file_path") ?: input.text("notebook_path"))?.let { Path.of(it) } ?: return Output()
        val absolute = real((if (target.isAbsolute) target else ws.root.resolve(target)).normalize())
        if (!absolute.startsWith(ws.root)) return Output()
        val relative = ws.root.relativize(absolute).toString().replace('\\', '/')
        if (Gate.isIgnored(relative)) return Output()
        blocked(task)?.let { return deny(it) }
        val violations = Gate.pathViolations(checkNotNull(task).stage.orEmpty(), task.feature, listOf(relative))
        if (violations.isNotEmpty()) return deny("${violations.first()}. 지금 단계(${task.stage})에서 고칠 수 있는 경로: ${IlsimMcp.allowed(task.stage, task.feature)}")
        return Output()
    }

    private fun blocked(task: ActiveTask?): String? = when {
        task == null -> "이 저장소는 일심 관리 대상입니다. 파일을 고치기 전에 ilsim MCP의 start_task로 작업을 시작하세요(진행 중 작업이면 next_step)"
        task.status == "WAITING_APPROVAL" -> "${task.stage} 단계 결과가 사용자 승인을 기다리는 중이라 수정할 수 없습니다. 승인된 뒤 next_step을 호출하세요"
        task.status != IlsimMcp.RUNNING -> "현재 단계가 시작되지 않았습니다(상태 ${task.status ?: "-"}). next_step을 호출하세요"
        else -> null
    }

    private fun stop(ws: Workspace, task: ActiveTask?, active: ActiveTasks): Output {
        if (task == null || task.status != IlsimMcp.RUNNING) return Output()
        if (ws.changedFiles().isEmpty() || task.stopBlocks >= MAX_STOP_BLOCKS) return Output()
        active.save(ws.root, task.copy(stopBlocks = task.stopBlocks + 1))
        val reason = "일심 ${task.stage} 단계의 변경이 아직 제출되지 않았습니다. submit_result로 제출해 통과시키세요. 통과시키지 못하면 이유와 사용자가 정할 일을 보고하고 끝내세요"
        return Output(buildJsonObject { put("decision", "block"); put("reason", reason) }.toString())
    }

    private fun deny(reason: String): Output = decide("deny", reason)

    private fun ask(reason: String): Output = decide("ask", reason)

    private fun decide(decision: String, reason: String): Output = Output(buildJsonObject {
        putJsonObject("hookSpecificOutput") {
            put("hookEventName", "PreToolUse")
            put("permissionDecision", decision)
            put("permissionDecisionReason", reason)
        }
    }.toString())

    private fun real(path: Path): Path {
        var existing = path
        while (!existing.toFile().exists()) existing = existing.parent ?: return path
        return existing.toRealPath().resolve(existing.relativize(path))
    }

    private fun JsonObject.text(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
}
