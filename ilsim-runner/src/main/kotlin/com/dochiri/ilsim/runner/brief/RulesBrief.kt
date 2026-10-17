package com.dochiri.ilsim.runner.brief

import com.dochiri.ilsim.runner.architecture.ArchitectureModel
import com.dochiri.ilsim.runner.convention.ConventionModel
import com.dochiri.ilsim.runner.risk.RiskConfig
import com.dochiri.ilsim.runner.risk.RiskRules

/**
 * 승인된 기존 규칙을 Agent 프롬프트에 붙일 요약으로 만든다. Agent가 위반하고 재시도하며 배우지 않고 처음부터 따르게 하기 위함이다.
 * 설계를 평가하거나 새로 제안하지 않고, 이 프로젝트가 이미 지키는 규칙만 전달한다.
 */
object RulesBrief {
    private const val LIMIT = 30
    private val evidenceSuffix = Regex("""\s*\((관측|허용|근거)[^)]*\)\s*$""")

    fun build(architecture: ArchitectureModel?, convention: ConventionModel?, risk: RiskConfig = RiskConfig()): String? {
        val arch = architecture?.rules.orEmpty().filter { it.approved && !it.rejected }
            .sortedBy { if (it.proposedSeverity == "BLOCK") 0 else 1 }.take(LIMIT).map { clean(it.description) }
        val conv = convention?.rules.orEmpty().filter { it.approved && !it.rejected }
            .sortedBy { if (it.severity == "BLOCK") 0 else 1 }.take(LIMIT).map { clean(it.description) }
        val risks = RiskRules.all.filter { (risk.overrides[it.id] ?: it.defaultSeverity) == "BLOCK" }.map { "${it.id} ${it.title}" }
        if (arch.isEmpty() && conv.isEmpty() && risks.isEmpty()) return null
        return buildString {
            appendLine("## 이 프로젝트의 기존 규칙 (반드시 따를 것)")
            appendLine("새 구조나 새 관행을 만들지 말고 기존 코드의 방식을 따르라. 아래를 어기면 검증에서 실패해 다시 작성해야 한다.")
            section("아키텍처", arch)
            section("코드 컨벤션", conv)
            section("쓰지 말아야 할 위험 패턴", risks)
        }.trimEnd()
    }

    private fun StringBuilder.section(title: String, items: List<String>) {
        if (items.isEmpty()) return
        appendLine()
        appendLine("### $title")
        items.forEach { appendLine("- $it") }
    }

    private fun clean(description: String) = description.replace(evidenceSuffix, "")
}
