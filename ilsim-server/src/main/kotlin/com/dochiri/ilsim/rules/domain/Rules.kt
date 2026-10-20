package com.dochiri.ilsim.rules.domain

enum class RuleSet(val fileName: String) {
    ARCHITECTURE("architecture.json"),
    CONVENTION("convention.json"),
    ;

    companion object {
        fun parse(raw: String): RuleSet = entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?: throw com.dochiri.ilsim.rules.domain.exception.InvalidRuleRequestException("알 수 없는 규칙 종류: $raw")
    }
}

enum class Decision { APPROVE, REJECT }

data class RuleView(
    val id: String,
    val kind: String,
    val description: String,
    val severity: String?,
    val approved: Boolean,
    val rejected: Boolean,
    val decidedBy: String?,
    val reviewVerdict: String?,
    val reviewReason: String?,
)

data class MemoryView(
    val id: String,
    val kind: String,
    val ruleId: String,
    val file: String,
    val summary: String,
    val recommendation: String,
    val status: String,
    val createdAt: String?,
    val approvedAt: String?,
    val supersededReason: String?,
)

data class Knowledge(val architecture: List<RuleView>, val convention: List<RuleView>, val memory: List<MemoryView>)
