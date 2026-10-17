package com.dochiri.ilsim.runner.convention

import com.dochiri.ilsim.runner.architecture.Evidence
import com.dochiri.ilsim.runner.architecture.ModelReview
import com.dochiri.ilsim.runner.architecture.RuleReview
import kotlinx.serialization.Serializable

/** 코드 컨벤션 규칙 한 건. 채굴한 규칙은 승인 전까지 후보이고, 기본 컨벤션의 규칙은 처음부터 승인된 상태로 만든다. */
@Serializable
data class ConventionRule(
    val id: String,
    val kind: String,
    val description: String,
    val params: Map<String, List<String>> = emptyMap(),
    val severity: String = "BLOCK",
    val evidence: Evidence = Evidence(0, 0),
    val approved: Boolean = false,
    val rejected: Boolean = false,
    val decidedBy: String? = null,
    val review: RuleReview? = null,
)

@Serializable
data class ConventionModel(
    val projectName: String?,
    val sourceFiles: Int,
    val testFiles: Int,
    val languages: Map<String, Int>,
    val rules: List<ConventionRule>,
    val notes: List<String> = emptyList(),
    /** MINED(분석으로 추론) 또는 DEFAULT:<표준 id>(신규 프로젝트에 지정한 기본 컨벤션). */
    val source: String = "MINED",
    val review: ModelReview? = null,
)
