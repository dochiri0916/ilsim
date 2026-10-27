package com.dochiri.ilsim.runner.brief

import com.dochiri.ilsim.runner.architecture.ArchitectureModel
import com.dochiri.ilsim.runner.convention.ConventionModel
import com.dochiri.ilsim.runner.convention.DocRules
import com.dochiri.ilsim.runner.risk.RiskConfig
import com.dochiri.ilsim.runner.risk.RiskRules

object RulesBrief {
    private const val LIMIT = 30
    private val evidenceSuffix = Regex("""\s*\((관측|허용|근거)[^)]*\)\s*$""")

    fun build(architecture: ArchitectureModel?, convention: ConventionModel?, risk: RiskConfig = RiskConfig()): String? {
        val arch = architecture?.rules.orEmpty().filter { it.approved && !it.rejected }
            .sortedBy { if (it.proposedSeverity == "BLOCK") 0 else 1 }.take(LIMIT).map { clean(it.description) }
        val (documented, observed) = convention?.rules.orEmpty().filter { it.approved && !it.rejected }.partition { DocRules.isDocRule(it.id) }
        val conv = observed.sortedBy { if (it.severity == "BLOCK") 0 else 1 }.take(LIMIT).map { clean(it.description) }
        val docs = documented.take(LIMIT).map { r -> r.description + r.origin?.let { " (${it.path}:${it.line})" }.orEmpty() }
        val risks = RiskRules.all.filter { (risk.overrides[it.id] ?: it.defaultSeverity) == "BLOCK" }.map { "${it.id} ${it.title}" }
        if (arch.isEmpty() && conv.isEmpty() && docs.isEmpty() && risks.isEmpty()) return null
        return buildString {
            appendLine("## 이 프로젝트의 기존 규칙 (반드시 따를 것)")
            appendLine("새 구조나 새 관행을 만들지 말고 기존 코드의 방식을 따르라. 아래를 어기면 검증에서 실패해 다시 작성해야 한다.")
            section("아키텍처", arch)
            section("코드 컨벤션", conv)
            section("문서 규칙", docs)
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
