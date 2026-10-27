package com.dochiri.ilsim.runner.architecture

import kotlinx.serialization.Serializable

@Serializable
data class Layout(
    val rootPrefix: String,
    val mode: String,
    val slices: List<String> = emptyList(),
    val layerNames: List<String> = emptyList(),
    val shared: List<String> = emptyList(),
    val observed: List<String> = emptyList(),
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
    val approved: Boolean = false,
    val rejected: Boolean = false,
    val decidedBy: String? = null,
    val review: RuleReview? = null,
    val recommendation: Recommendation? = null,
)

@Serializable
data class Recommendation(val principle: String, val rationale: String)

@Serializable
data class RuleReview(val verdict: String, val reason: String, val evidenceHash: String = "")

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
    val applicability: Applicability = Applicability(Applicability.APPLICABLE),
    val source: String = "MINED",
    val review: ModelReview? = null,
)

@Serializable
data class Violation(val ruleId: String, val severity: String, val file: String, val line: Int, val message: String)
