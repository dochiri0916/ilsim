package com.dochiri.ilsim.task.domain

import com.dochiri.ilsim.task.domain.vo.Stage
import com.dochiri.ilsim.task.domain.vo.TaskStatus

data class AttemptRecord(val stage: String, val passed: Boolean, val cause: String?, val claudeCalls: Int, val jevUsd: Double, val claudeUsd: Double) {
    companion object {
        const val EVENT_TYPE = "attempt"

        fun parse(stage: String?, data: String?): AttemptRecord? {
            val fields = data?.split(';')?.mapNotNull { part -> part.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }?.toMap() ?: return null
            val passed = fields["passed"]?.toBooleanStrictOrNull() ?: return null
            val name = fields["stage"]?.takeIf { it.isNotEmpty() } ?: stage ?: return null
            return AttemptRecord(name, passed, fields["cause"]?.takeIf { it.isNotEmpty() }, fields["claude"]?.toIntOrNull() ?: 0,
                fields["jevUsd"]?.toDoubleOrNull() ?: 0.0, fields["claudeUsd"]?.toDoubleOrNull() ?: 0.0)
        }
    }
}

data class StageMetrics(val stage: String, val submissions: Int, val failures: Int, val causes: Map<String, Int>, val claudeCalls: Int, val jevUsd: Double, val claudeUsd: Double)

data class TaskSummary(val taskId: String, val feature: String, val status: String, val branch: String?, val submissions: Int, val failures: Int, val claudeCalls: Int, val costUsd: Double)

data class TaskMetrics(val tasks: Int, val completed: Int, val blocked: Int, val active: Int, val stages: List<StageMetrics>, val summaries: List<TaskSummary>) {
    companion object {
        fun of(tasks: Collection<Task>, attempts: Map<String, List<AttemptRecord>>): TaskMetrics {
            val records = tasks.flatMap { attempts[it.id.value].orEmpty() }
            val order = Stage.entries.map { it.name }
            val stages = records.groupBy { it.stage }.entries.sortedBy { order.indexOf(it.key).takeIf { i -> i >= 0 } ?: order.size }.map { (stage, group) ->
                StageMetrics(stage, group.size, group.count { !it.passed }, group.filter { !it.passed }.groupingBy { it.cause ?: "unknown" }.eachCount(),
                    group.sumOf { it.claudeCalls }, group.sumOf { it.jevUsd }, group.sumOf { it.claudeUsd })
            }
            val summaries = tasks.sortedByDescending { it.createdAt }.map { task ->
                val own = attempts[task.id.value].orEmpty()
                TaskSummary(task.id.value, task.feature.value, task.status.name, task.branch?.value, own.size, own.count { !it.passed },
                    own.sumOf { it.claudeCalls }, own.sumOf { it.jevUsd + it.claudeUsd })
            }
            return TaskMetrics(tasks.size, tasks.count { it.status == TaskStatus.COMPLETED }, tasks.count { it.status == TaskStatus.BLOCKED },
                tasks.count { it.status == TaskStatus.ACTIVE }, stages, summaries)
        }
    }
}
