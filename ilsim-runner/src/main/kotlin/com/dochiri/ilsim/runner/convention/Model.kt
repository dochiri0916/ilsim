package com.dochiri.ilsim.runner.convention

import com.dochiri.ilsim.runner.architecture.Evidence
import com.dochiri.ilsim.runner.architecture.ModelReview
import com.dochiri.ilsim.runner.architecture.Recommendation
import com.dochiri.ilsim.runner.architecture.RuleReview
import kotlinx.serialization.Serializable

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
    val recommendation: Recommendation? = null,
    val origin: DocOrigin? = null,
)

@Serializable
data class DocOrigin(val path: String, val line: Int, val heading: String, val quote: String, val hash: String)

@Serializable
data class DocCoverage(
    val path: String,
    val line: Int,
    val heading: String,
    val hash: String,
    val rules: List<String> = emptyList(),
    val remaining: List<String> = emptyList(),
)

@Serializable
data class ConventionModel(
    val projectName: String?,
    val sourceFiles: Int,
    val testFiles: Int,
    val languages: Map<String, Int>,
    val rules: List<ConventionRule>,
    val notes: List<String> = emptyList(),
    val source: String = "MINED",
    val review: ModelReview? = null,
    val inventory: List<String> = emptyList(),
    val documents: List<DocCoverage> = emptyList(),
)
