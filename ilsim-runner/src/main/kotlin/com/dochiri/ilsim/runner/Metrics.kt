package com.dochiri.ilsim.runner

import java.nio.file.Path
import kotlinx.serialization.Serializable

@Serializable
data class StageMetrics(val stage: String, val submissions: Int, val failures: Int, val causes: Map<String, Int> = emptyMap(), val claudeCalls: Int = 0, val jevUsd: Double = 0.0, val claudeUsd: Double = 0.0)

@Serializable
data class TaskSummary(val taskId: String, val feature: String, val status: String, val branch: String? = null, val submissions: Int = 0, val failures: Int = 0, val claudeCalls: Int = 0, val costUsd: Double = 0.0)

@Serializable
data class TaskMetrics(val tasks: Int, val completed: Int, val blocked: Int, val active: Int, val stages: List<StageMetrics> = emptyList(), val summaries: List<TaskSummary> = emptyList())

object MetricsReport {
    fun merged(root: Path, summaries: List<TaskSummary>): Map<String, Boolean?> = summaries.filter { it.status == "COMPLETED" && it.branch != null }.associate { s ->
        val exists = Shell.git(root, "rev-parse", "--verify", "--quiet", "refs/heads/${s.branch}").exit == 0
        s.taskId to if (!exists) null else Shell.git(root, "merge-base", "--is-ancestor", "refs/heads/${s.branch}", "HEAD").exit == 0
    }

    fun render(project: String, m: TaskMetrics, merged: Map<String, Boolean?>): String = buildString {
        appendLine("[일심 지표] $project: 작업 ${m.tasks}개(완료 ${m.completed} · 차단 ${m.blocked} · 진행 ${m.active})")
        val known = merged.values.filterNotNull()
        if (merged.isNotEmpty()) appendLine("완료 작업 중 현재 브랜치에 병합 ${known.count { it }}/${merged.size}${if (known.size < merged.size) " (브랜치 없음 ${merged.size - known.size})" else ""}")
        if (m.stages.isEmpty()) { append("제출 기록이 없습니다(제출 기록은 이 기능 이후 작업부터 쌓입니다)"); return@buildString }
        appendLine()
        appendLine("단계 | 제출 | 실패 | 실패 원인 | Claude 호출 | JEV 비용 | Claude 비용")
        m.stages.forEach { s ->
            appendLine("${s.stage} | ${s.submissions} | ${s.failures} | ${s.causes.entries.sortedByDescending { it.value }.joinToString { "${it.key} ${it.value}" }.ifEmpty { "-" }} | ${s.claudeCalls} | $${"%.4f".format(s.jevUsd)} | $${"%.4f".format(s.claudeUsd)}")
        }
        val submissions = m.stages.sumOf { it.submissions }
        val calls = m.stages.sumOf { it.claudeCalls }
        appendLine()
        appendLine("제출 ${submissions}회 중 실패 ${m.stages.sumOf { it.failures }}회, Claude 호출 ${calls}회(제출당 ${"%.2f".format(if (submissions == 0) 0.0 else calls.toDouble() / submissions)}), 비용 JEV $${"%.4f".format(m.stages.sumOf { it.jevUsd })} · Claude $${"%.4f".format(m.stages.sumOf { it.claudeUsd })}")
        appendLine()
        appendLine("작업 | 상태 | 제출 | 실패 | Claude 호출 | 비용 | 병합")
        m.summaries.forEach { t ->
            appendLine("${t.feature} (${t.taskId.take(8)}) | ${t.status} | ${t.submissions} | ${t.failures} | ${t.claudeCalls} | $${"%.4f".format(t.costUsd)} | ${when (merged[t.taskId]) { true -> "병합"; false -> "안 함"; null -> "-" }}")
        }
    }.trimEnd()
}
