package com.dochiri.ilsim.runner.architecture

import com.dochiri.ilsim.runner.document.DocumentContext
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 정적으로 채굴한 결과를 AI가 한 번 검토한다. 입력은 소스가 아니라 채굴 요약뿐이며,
 * 결과는 규칙 후보에 붙는 의견이다. 승인·거절과 위반 판정은 하지 않는다.
 */
object ArchitectureReviewer {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    internal data class RuleOpinion(val id: String, val verdict: String, val reason: String = "")

    @Serializable
    internal data class Opinion(
        val rules: List<RuleOpinion> = emptyList(),
        val missing: List<String> = emptyList(),
        val applicability: String? = null,
    )

    /** 모델에서 AI에 보낼 요약을 결정론적으로 만든다. 승인 상태와 이전 의견은 넣지 않아 같은 구조면 해시가 같다. */
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

    fun hash(m: ArchitectureModel, docs: DocumentContext? = null): String =
        MessageDigest.getInstance("SHA-256").digest((summary(m) + (docs?.hash.orEmpty())).toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    fun prompt(m: ArchitectureModel, docs: DocumentContext? = null): String = """
        아래는 소스 코드의 package/import 를 정적 분석해 얻은 한 프로젝트의 아키텍처 규칙 후보다. 소스는 보지 않았다.
        설계가 좋은지 나쁜지는 판단하지 마라. 이 프로젝트가 실제로 지키고 있는 기존 규칙인지만 가려라. JSON 만 출력하라(설명·코드펜스 금지).

        {"rules":[{"id":"<규칙 id>","verdict":"KEEP|SUSPECT","reason":"한 문장"}],
         "missing":["문서에 명시되어 있지만 후보에 없는 규칙 한 문장 (문서가 없으면 빈 배열)"],
         "applicability":"관측 근거가 규칙을 만들기에 충분한지 한 문장"}

        기준: 관측이 일관되어 이 프로젝트가 의도적으로 따르는 규칙이면 KEEP, 관측이 적거나 우연히 그렇게 보일 수 있으면 SUSPECT.
        모든 규칙 id 에 대해 답하라. 없는 id 를 만들지 마라.

        ${summary(m)}
        ${docSection(docs)}
    """.trimIndent()

    /** 프로젝트 문서가 있으면 목적별 기준과 발췌를 프롬프트에 넣는다. */
    internal fun docSection(docs: DocumentContext?): String =
        if (docs == null || docs.isEmpty) "" else
            "\n프로젝트 문서(${docs.purpose.label}) 검토 기준: ${docs.purpose.criteria}\n" +
                "문서와 충돌하는 규칙은 verdict 를 SUSPECT 로 하고 reason 에 문서의 어떤 내용과 충돌하는지 쓰라. 문서에만 있고 규칙에 없는 항목은 missing 에 쓰라.\n\n${docs.text}"


    /** 이미 같은 요약을 검토했으면 [ask]를 부르지 않고 그대로 돌려준다. */
    fun review(m: ArchitectureModel, reviewer: String, force: Boolean = false, docs: DocumentContext? = null, ask: (String) -> String): ArchitectureModel {
        val h = hash(m, docs)
        if (!force && m.review?.modelHash == h) return m
        return apply(m, ask(prompt(m, docs)), h, reviewer)
    }

    internal fun apply(m: ArchitectureModel, response: String, hash: String, reviewer: String): ArchitectureModel {
        val opinion = parseOpinion(response)
        val byId = opinion.rules.filter { it.verdict in verdicts }.associateBy { it.id }
        return m.copy(
            rules = m.rules.map { r -> byId[r.id]?.let { r.copy(review = RuleReview(it.verdict, it.reason)) } ?: r },
            review = ModelReview(hash, reviewer, opinion.missing, opinion.applicability),
        )
    }

    internal val verdicts = setOf("KEEP", "SUSPECT", "DROP")

    /** AI 응답에서 첫 `{` 부터 마지막 `}` 까지를 JSON 으로 읽는다. */
    internal fun parseOpinion(response: String): Opinion {
        val start = response.indexOf('{')
        val end = response.lastIndexOf('}')
        require(start in 0 until end) { "AI 응답에서 JSON 을 찾지 못했습니다" }
        return try {
            json.decodeFromString<Opinion>(response.substring(start, end + 1))
        } catch (e: Exception) {
            throw IllegalArgumentException("AI 응답을 해석하지 못했습니다: ${e.message}")
        }
    }
}
