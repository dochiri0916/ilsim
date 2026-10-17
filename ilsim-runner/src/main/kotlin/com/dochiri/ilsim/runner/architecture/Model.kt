package com.dochiri.ilsim.runner.architecture

import kotlinx.serialization.Serializable

/** 패키지 트리에서 추론한 레이아웃. mode: SLICED(반복되는 하위 구조가 있는 슬라이스들) | FLAT(루트 아래 단위들) | UNKNOWN. */
@Serializable
data class Layout(
    val rootPrefix: String,
    val mode: String,
    val slices: List<String> = emptyList(),
    val layerNames: List<String> = emptyList(),
)

@Serializable
data class EdgeStat(val from: String, val to: String, val files: Int, val examples: List<String> = emptyList())

@Serializable
data class LayerInfo(val name: String, val files: Int, val externals: List<String>)

@Serializable
data class Evidence(val compliant: Int, val total: Int, val examples: List<String> = emptyList())

@Serializable
data class ArchRule(
    val id: String,
    val kind: String,
    val description: String,
    val params: Map<String, List<String>> = emptyMap(),
    val proposedSeverity: String,
    val evidence: Evidence,
    /** 사용자 승인 전에는 후보다. */
    val approved: Boolean = false,
    /** 사용자가 거절한 규칙은 어떤 검사에서도 적용하지 않는다. */
    val rejected: Boolean = false,
    /** 승인·거절을 누가 했는지: user | ai-review. 결정이 없으면 null. */
    val decidedBy: String? = null,
    /** AI 검토 의견. 승인 판단의 참고일 뿐 규칙의 적용 여부에는 영향을 주지 않는다. */
    val review: RuleReview? = null,
)

/** verdict: KEEP | SUSPECT | DROP */
@Serializable
data class RuleReview(val verdict: String, val reason: String)

/** AI 검토 결과 중 개별 규칙에 속하지 않는 부분. modelHash 는 검토한 요약의 해시로, 같으면 다시 검토하지 않는다. */
@Serializable
data class ModelReview(
    val modelHash: String,
    val reviewer: String,
    val missingRules: List<String> = emptyList(),
    val applicabilityOpinion: String? = null,
)

@Serializable
data class ArchitectureModel(
    val projectName: String?,
    val sourceFiles: Int,
    val layout: Layout,
    val layers: List<LayerInfo>,
    val layerLevels: List<List<String>>,
    val layerEdges: List<EdgeStat>,
    val sliceEdges: List<EdgeStat>,
    val rules: List<ArchRule>,
    val notes: List<String>,
    /** 적용 가능성 판정. 이 필드가 없던 이전 저장본은 전체 적용으로 읽는다. */
    val applicability: Applicability = Applicability(Applicability.APPLICABLE),
    /** MINED(분석으로 추론) 또는 DEFAULT:<표준 id>(신규 프로젝트에 지정한 기본 아키텍처). */
    val source: String = "MINED",
    val review: ModelReview? = null,
)

@Serializable
data class Violation(val ruleId: String, val severity: String, val file: String, val line: Int, val message: String)
