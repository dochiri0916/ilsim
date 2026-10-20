package com.dochiri.ilsim.runner.architecture

import com.dochiri.ilsim.runner.document.DocumentContext
import com.dochiri.ilsim.runner.ClaudeModels
import com.dochiri.ilsim.runner.ReviewCache
import com.dochiri.ilsim.runner.RuleReviewReuse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

object ArchitectureReviewer {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    internal data class RuleOpinion(val id: String, val verdict: String, val reason: String = "")

    @Serializable
    internal data class Opinion(
        val rules: List<RuleOpinion> = emptyList(),
        val missing: List<String> = emptyList(),
        val applicability: String? = null,
        @kotlinx.serialization.Transient val completeFields: Boolean = false,
    )

    fun summary(m: ArchitectureModel): String = buildString {
        appendLine("project=${m.projectName} files=${m.sourceFiles} layout=${m.layout.mode} root=${m.layout.rootPrefix}")
        if (m.layout.slices.isNotEmpty()) appendLine("slices=${m.layout.slices} layers=${m.layout.layerNames}")
        appendLine("applicability=${m.applicability.status} reasons=${m.applicability.reasons}")
        appendLine("units:")
        m.layers.forEach { appendLine("  ${it.name} files=${it.files} externals=${it.externals}") }
        appendLine("unit-edges:")
        m.layerEdges.forEach { appendLine("  ${it.from}->${it.to} files=${it.files}") }
        if (m.sliceEdges.isNotEmpty()) {
            appendLine("slice-edges:")
            m.sliceEdges.forEach { appendLine("  ${it.from}->${it.to} files=${it.files}") }
        }
        appendLine("rules:")
        m.rules.forEach { appendLine("  ${it.id} [${it.kind}/${it.proposedSeverity}] ${it.description} evidence=${it.evidence.compliant}/${it.evidence.total}") }
        if (m.notes.isNotEmpty()) appendLine("notes=${m.notes}")
    }

    fun hash(m: ArchitectureModel, docs: DocumentContext? = null, reviewer: String = ClaudeModels.REVIEW): String =
        ReviewCache.digest("architecture-review-v2|$reviewer|${ClaudeModels.EFFORT}|${com.dochiri.ilsim.runner.ReviewRouter.policyKey}|" + prompt(m, docs))

    fun prompt(m: ArchitectureModel, docs: DocumentContext? = null): String = """
        아래는 소스 코드의 package/import 를 정적 분석해 얻은 한 프로젝트의 아키텍처 규칙 후보다. 소스는 보지 않았다.
        설계가 좋은지 나쁜지는 판단하지 마라. 이 프로젝트가 실제로 지키고 있는 기존 규칙인지만 가려라. JSON 만 출력하라(설명·코드펜스 금지).

        {"rules":[{"id":"<규칙 id>","verdict":"KEEP|SUSPECT","reason":"한 문장"}],
         "missing":["문서에 명시되어 있지만 후보에 없는 규칙 한 문장 (문서가 없으면 빈 배열)"],
         "applicability":"관측 근거가 규칙을 만들기에 충분한지 한 문장"}

        기준: 관측에 예외가 없고 계층 구조상 자연스러운 규칙이면 단위의 파일 수가 적어도 KEEP 한다(작은 프로젝트에서는 파일 1개 단위가 흔하다). 다른 규칙의 부수 효과이거나 서로 모순되거나 구조상 의미가 없으면 SUSPECT.
        모든 규칙 id 에 대해 답하라. 없는 id 를 만들지 마라.

        ${docSection(docs)}
        ${summary(m)}
    """.trimIndent()

    internal fun docSection(docs: DocumentContext?): String =
        if (docs == null || docs.isEmpty) "" else
            "\n프로젝트 문서(${docs.purpose.label}) 검토 기준: ${docs.purpose.criteria}\n" +
                "문서와 충돌하는 규칙은 verdict 를 SUSPECT 로 하고 reason 에 문서의 어떤 내용과 충돌하는지 쓰라. 문서에만 있고 규칙에 없는 항목은 missing 에 쓰라.\n\n${docs.text}"


    fun review(m: ArchitectureModel, reviewer: String, force: Boolean = false, docs: DocumentContext? = null, ask: (String) -> String): ArchitectureModel {
        val h = hash(m, docs, reviewer)
        if (!force && m.review?.modelHash == h) return m
        val hashes = ruleHashes(m, docs, reviewer)
        val reused = if (force) emptyMap() else m.rules.mapNotNull { rule ->
            rule.review?.takeIf { it.evidenceHash == hashes[rule.id] && it.verdict in verdicts && it.reason.isNotBlank() }?.let { rule.id to it }
        }.toMap()
        val reviewed = apply(m, RuleReviewReuse.response(prompt(m, docs), m.rules.map { it.id }, reused, ask), h, reviewer)
        return if (reviewed.review?.modelHash != h) reviewed else reviewed.copy(rules = reviewed.rules.map { it.copy(review = it.review?.copy(evidenceHash = hashes.getValue(it.id))) })
    }

    private fun ruleHashes(m: ArchitectureModel, docs: DocumentContext?, reviewer: String): Map<String, String> {
        val definitions = m.rules.map { it.copy(evidence = Evidence(0, 0), approved = false, rejected = false, decidedBy = null, review = null) }
        val common = "$reviewer/${ClaudeModels.EFFORT}/${com.dochiri.ilsim.runner.ReviewRouter.policyKey}/${docSection(docs)}/${summary(m.copy(rules = emptyList()))}/$definitions"
        return m.rules.associate { it.id to ReviewCache.digest("arch-rule-v1/$common/${it.copy(approved = false, rejected = false, decidedBy = null, review = null)}") }
    }

    internal fun apply(m: ArchitectureModel, response: String, hash: String, reviewer: String): ArchitectureModel {
        val opinion = parseOpinion(response)
        val byId = opinion.rules.filter { it.verdict in verdicts && it.reason.isNotBlank() }.associateBy { it.id }
        val complete = complete(opinion, m.rules.map { it.id })
        return m.copy(
            rules = m.rules.map { r -> byId[r.id]?.let { r.copy(review = RuleReview(it.verdict, it.reason)) } ?: r },
            review = ModelReview(if (complete) hash else "", reviewer, opinion.missing, opinion.applicability),
        )
    }

    internal val verdicts = setOf("KEEP", "SUSPECT", "DROP")

    internal fun complete(opinion: Opinion, expected: List<String>): Boolean =
        opinion.completeFields && opinion.applicability?.isNotBlank() == true && opinion.rules.size == expected.size &&
            opinion.rules.map { it.id }.toSet() == expected.toSet() &&
            opinion.rules.all { it.verdict in verdicts && it.reason.isNotBlank() }

    internal fun parseOpinion(response: String): Opinion {
        val start = response.indexOf('{')
        val end = response.lastIndexOf('}')
        require(start in 0 until end) { "AI 응답에서 JSON 을 찾지 못했습니다" }
        return try {
            val obj = json.parseToJsonElement(response.substring(start, end + 1)).jsonObject
            json.decodeFromJsonElement<Opinion>(obj).copy(completeFields = obj["rules"] is JsonArray && obj["missing"] is JsonArray && obj.containsKey("applicability"))
        } catch (e: Exception) {
            throw IllegalArgumentException("AI 응답을 해석하지 못했습니다: ${e.message}")
        }
    }
}
