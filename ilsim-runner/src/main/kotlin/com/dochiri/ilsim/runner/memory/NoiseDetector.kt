package com.dochiri.ilsim.runner.memory

import com.dochiri.ilsim.runner.architecture.Violation
import com.dochiri.ilsim.runner.risk.RiskConfig

data class NoiseProposal(val ruleId: String, val dismissed: Int, val total: Int)

object NoiseDetector {
    const val MIN_DISMISSED = 3
    const val MIN_RATIO = 0.5

    fun proposals(config: RiskConfig, current: List<Violation>, memory: List<MemoryEntry>): List<NoiseProposal> {
        val dismissed = memory.filter { it.status == "ACTIVE" && it.kind == "FALSE_POSITIVE" && it.source == "user" }.groupingBy { it.ruleId }.eachCount()
        val remaining = current.groupingBy { it.ruleId }.eachCount()
        return dismissed.mapNotNull { (rule, n) ->
            val total = n + remaining[rule].orEmpty()
            if (rule !in config.aiReview && n >= MIN_DISMISSED && n.toDouble() / total >= MIN_RATIO) NoiseProposal(rule, n, total) else null
        }.sortedBy { it.ruleId }
    }

    private fun Int?.orEmpty() = this ?: 0

    fun message(projectId: String, p: NoiseProposal) =
        "${p.ruleId}: 오탐으로 직접 제외한 후보가 ${p.dismissed}건(전체 ${p.total}건)입니다. 이 규칙에 AI 검토를 도입할까요? " +
            "승인: risk-ai $projectId approve ${p.ruleId} · 규칙 끄기: risk-set $projectId ${p.ruleId} OFF"
}
