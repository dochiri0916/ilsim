package com.dochiri.ilsim.runner.convention

import com.dochiri.ilsim.runner.architecture.ArchitectureReviewer
import com.dochiri.ilsim.runner.architecture.ModelReview
import com.dochiri.ilsim.runner.architecture.RuleReview
import com.dochiri.ilsim.runner.document.DocumentContext
import java.security.MessageDigest

/** 채굴한 컨벤션 요약을 AI가 한 번 검토한다. 아키텍처 검토와 같은 원칙: 소스는 보내지 않고, 의견만 붙이며, 승인·위반 판정은 하지 않는다. */
object ConventionReviewer {

    fun summary(m: ConventionModel): String = buildString {
        appendLine("project=${m.projectName} main=${m.sourceFiles} test=${m.testFiles} languages=${m.languages}")
        appendLine("rules:")
        m.rules.forEach { appendLine("  ${it.id} [${it.kind}/${it.severity}] ${it.description} evidence=${it.evidence.compliant}/${it.evidence.total}") }
        if (m.notes.isNotEmpty()) appendLine("notes=${m.notes}")
    }

    fun hash(m: ConventionModel, docs: DocumentContext? = null): String =
        MessageDigest.getInstance("SHA-256").digest((summary(m) + (docs?.hash.orEmpty())).toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    fun prompt(m: ConventionModel, docs: DocumentContext? = null): String = """
        아래는 소스를 정적 분석해 얻은 한 프로젝트의 코드 컨벤션 규칙 후보다. 소스는 보지 않았다.
        관행이 좋은지 나쁜지는 판단하지 마라. 이 프로젝트가 실제로 지키고 있는 기존 규칙인지만 가려라. JSON 만 출력하라(설명·코드펜스 금지).

        {"rules":[{"id":"<규칙 id>","verdict":"KEEP|SUSPECT","reason":"한 문장"}],
         "missing":["문서에 명시되어 있지만 후보에 없는 규칙 한 문장 (문서가 없으면 빈 배열)"],
         "applicability":"관측 근거가 규칙을 만들기에 충분한지 한 문장"}

        기준: 관측이 일관되어 이 팀이 의도적으로 따르는 규칙이면 KEEP, 관측이 적거나 다른 규칙의 부수 효과처럼 보이면 SUSPECT.
        모든 규칙 id 에 대해 답하라. 없는 id 를 만들지 마라.

        ${summary(m)}
        ${ArchitectureReviewer.docSection(docs)}
    """.trimIndent()

    fun review(m: ConventionModel, reviewer: String, force: Boolean = false, docs: DocumentContext? = null, ask: (String) -> String): ConventionModel {
        val h = hash(m, docs)
        if (!force && m.review?.modelHash == h) return m
        return apply(m, ask(prompt(m, docs)), h, reviewer)
    }

    internal fun apply(m: ConventionModel, response: String, hash: String, reviewer: String): ConventionModel {
        val opinion = ArchitectureReviewer.parseOpinion(response)
        val byId = opinion.rules.filter { it.verdict in ArchitectureReviewer.verdicts }.associateBy { it.id }
        return m.copy(
            rules = m.rules.map { r -> byId[r.id]?.let { r.copy(review = RuleReview(it.verdict, it.reason)) } ?: r },
            review = ModelReview(hash, reviewer, opinion.missing, opinion.applicability),
        )
    }
}

/** 컨벤션 규칙의 승인·거절. */
object ConventionApproval {
    fun approve(m: ConventionModel, ids: Collection<String>) = update(m, ids) { it.copy(approved = true, rejected = false, decidedBy = "user") }
    fun reject(m: ConventionModel, ids: Collection<String>) = update(m, ids) { it.copy(approved = false, rejected = true, decidedBy = "user") }
    fun keepIds(m: ConventionModel) = m.rules.filter { it.review?.verdict == "KEEP" && !it.approved && !it.rejected }.map { it.id }
    /** KEEP 이고 REVIEW 심각도인 미결정 규칙만 승인한다. 게이트를 막는 BLOCK 규칙은 사용자에게 넘긴다. */
    fun autoApprove(m: ConventionModel): ConventionModel = m.copy(rules = m.rules.map {
        if (it.review?.verdict == "KEEP" && it.severity != "BLOCK" && !it.approved && !it.rejected) it.copy(approved = true, decidedBy = "ai-review") else it
    })

    fun carryOver(previous: ConventionModel?, mined: ConventionModel): ConventionModel {
        if (previous == null) return mined
        return mined.copy(
            rules = mined.rules.map { r ->
                previous.rules.firstOrNull { it.id == r.id && it.kind == r.kind && it.params == r.params }
                    ?.let { o -> r.copy(approved = o.approved, rejected = o.rejected, decidedBy = o.decidedBy, review = o.review) } ?: r
            },
            review = previous.review,
        )
    }

    fun undecidedIds(m: ConventionModel) = m.rules.filter { !it.approved && !it.rejected }.map { it.id }

    private fun update(m: ConventionModel, ids: Collection<String>, change: (ConventionRule) -> ConventionRule): ConventionModel {
        val unknown = ids.filterNot { id -> m.rules.any { it.id == id } }
        require(unknown.isEmpty()) { "알 수 없는 규칙 id: ${unknown.joinToString()}" }
        return m.copy(rules = m.rules.map { if (it.id in ids) change(it) else it })
    }
}

object ConventionReport {
    fun summary(m: ConventionModel, selfCheck: List<com.dochiri.ilsim.runner.architecture.Violation>?): String = buildString {
        appendLine("컨벤션 추론: ${m.projectName} (main ${m.sourceFiles}개 · test ${m.testFiles}개 · ${m.languages})")
        appendLine("규칙 후보 ${m.rules.size}개 (모두 사용자 승인 전):")
        m.rules.forEach { appendLine("  - (${it.severity}) ${it.description}") }
        if (m.notes.isNotEmpty()) { appendLine(); appendLine("참고:"); m.notes.forEach { appendLine("  ! $it") } }
        if (selfCheck != null) {
            appendLine()
            appendLine("자기 검증(생성한 규칙을 현재 코드에 적용): 위반 ${selfCheck.size}건 (기존 코드의 예외)")
            selfCheck.take(10).forEach { appendLine("  ! ${it.ruleId} ${it.file}:${it.line} ${it.message}") }
        }
    }

    fun triage(m: ConventionModel, projectId: String): String = buildString {
        val auto = m.rules.count { it.approved && it.decidedBy == "ai-review" }
        val user = m.rules.count { it.decidedBy == "user" }
        val pending = m.rules.filter { !it.approved && !it.rejected }
        appendLine("규칙 ${m.rules.size}개: AI 검토 후 자동 승인 ${auto} · 사용자 결정 ${user} · 사용자 선택 필요 ${pending.size}")
        if (pending.isNotEmpty()) {
            appendLine("선택이 필요한 규칙(승인: approve $projectId <id> · 거절: reject $projectId <id>):")
            pending.forEach { appendLine("  [${it.review?.verdict ?: "의견 없음"}] ${it.id} (${it.severity}) ${it.review?.reason.orEmpty()}") }
        }
    }

    fun rules(m: ConventionModel): String = buildString {
        appendLine("규칙 ${m.rules.size}개 · 승인 ${m.rules.count { it.approved }} · 거절 ${m.rules.count { it.rejected }} · 미결정 ${m.rules.count { !it.approved && !it.rejected }}")
        m.rules.forEach {
            val state = if (it.approved) "승인" else if (it.rejected) "거절" else "후보"
            val ai = it.review?.let { r -> " AI:${r.verdict}${if (r.reason.isBlank()) "" else " - ${r.reason}"}" }.orEmpty()
            appendLine("  [$state] ${it.id} (${it.severity})$ai")
        }
        m.review?.let { r ->
            r.applicabilityOpinion?.let { appendLine("AI 의견(제외 항목): $it") }
            if (r.missingRules.isNotEmpty()) { appendLine("AI 제안(추가하면 좋을 컨벤션):"); r.missingRules.forEach { appendLine("  ? $it") } }
        }
    }
}
