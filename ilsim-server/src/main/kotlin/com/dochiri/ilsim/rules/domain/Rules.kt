package com.dochiri.ilsim.rules.domain

/** 규칙 모델 종류. 프로젝트별로 `~/.ilsim/projects/<id>/<파일>` 에 저장된다. */
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

/** 사용자가 승인하기 전에는 후보다. 거절한 규칙은 어떤 검사에서도 쓰이지 않는다. */
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

/** 검증된 학습. CANDIDATE(승인 대기) → ACTIVE(승인) / SUPERSEDED(거절·근거 소멸). */
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
