package com.dochiri.ilsim.runner.convention

import com.dochiri.ilsim.runner.architecture.ArchitectureReviewer
import com.dochiri.ilsim.runner.architecture.ModelReview
import com.dochiri.ilsim.runner.architecture.RuleReview
import com.dochiri.ilsim.runner.document.DocumentContext
import com.dochiri.ilsim.runner.ReviewCache
import com.dochiri.ilsim.runner.ReviewRouter
import com.dochiri.ilsim.runner.RuleReviewReuse
import com.dochiri.ilsim.runner.architecture.Evidence

object ConventionReviewer {

    fun summary(m: ConventionModel): String = buildString {
        appendLine("project=${m.projectName} main=${m.sourceFiles} test=${m.testFiles} languages=${m.languages}")
        appendLine("rules:")
        m.rules.forEach { appendLine("  ${it.id} [${it.kind}/${it.severity}] ${it.description} evidence=${it.evidence.compliant}/${it.evidence.total}") }
        if (m.inventory.isNotEmpty()) { appendLine("packages:"); m.inventory.forEach { appendLine("  $it") } }
        if (m.notes.isNotEmpty()) appendLine("notes=${m.notes}")
    }

    fun hash(m: ConventionModel, docs: DocumentContext? = null, reviewer: String = com.dochiri.ilsim.runner.ClaudeModels.REVIEW): String =
        com.dochiri.ilsim.runner.ReviewCache.digest("convention-review-v2|$reviewer|${com.dochiri.ilsim.runner.ClaudeModels.EFFORT}|${com.dochiri.ilsim.runner.ReviewRouter.policyKey}|" + prompt(m, docs))

    fun prompt(m: ConventionModel, docs: DocumentContext? = null): String = """
        아래는 소스를 정적 분석해 얻은 한 프로젝트의 코드 컨벤션 규칙 후보다. 소스는 보지 않았다.
        관행이 좋은지 나쁜지는 판단하지 마라. 이 프로젝트가 실제로 지키고 있는 기존 규칙인지만 가려라. JSON 만 출력하라(설명·코드펜스 금지).

        {"rules":[{"id":"<규칙 id>","verdict":"KEEP|SUSPECT","reason":"한 문장"}],
         "missing":["문서 또는 packages 목록에서 보이지만 후보에 없는 관행 한 문장 (없으면 빈 배열)"],
         "applicability":"관측 근거가 규칙을 만들기에 충분한지 한 문장"}

        기준: 관측에 예외가 없고 널리 쓰이는 관행(계층별 패키지·접미사 명명, 생성자 주입, 포맷)이면 관측 수가 적어도 KEEP 한다. 다른 규칙의 부수 효과이거나 서로 중복되거나 관행으로서 의미가 없으면 SUSPECT.
        모든 규칙 id 에 대해 답하라. 없는 id 를 만들지 마라.

        ${ArchitectureReviewer.docSection(docs)}
        ${summary(m)}
    """.trimIndent()

    fun review(m: ConventionModel, reviewer: String, force: Boolean = false, docs: DocumentContext? = null, ask: (String) -> String): ConventionModel {
        val h = hash(m, docs, reviewer)
        if (!force && m.review?.modelHash == h) return m
        val hashes = ruleHashes(m, docs, reviewer)
        val reused = if (force) emptyMap() else m.rules.mapNotNull { rule ->
            rule.review?.takeIf { it.evidenceHash == hashes[rule.id] && it.verdict in ArchitectureReviewer.verdicts && it.reason.isNotBlank() }?.let { rule.id to it }
        }.toMap()
        val reviewed = apply(m, RuleReviewReuse.response(prompt(m, docs), m.rules.map { it.id }, reused, ask), h, reviewer)
        return if (reviewed.review?.modelHash != h) reviewed else reviewed.copy(rules = reviewed.rules.map { it.copy(review = it.review?.copy(evidenceHash = hashes.getValue(it.id))) })
    }

    private fun ruleHashes(m: ConventionModel, docs: DocumentContext?, reviewer: String): Map<String, String> {
        val definitions = m.rules.map { it.copy(evidence = Evidence(0, 0), approved = false, rejected = false, decidedBy = null, review = null) }
        val common = "$reviewer/${com.dochiri.ilsim.runner.ClaudeModels.EFFORT}/${ReviewRouter.policyKey}/${ArchitectureReviewer.docSection(docs)}/${m.projectName}/${m.source}/${m.languages.keys}/${m.notes}/$definitions"
        return m.rules.associate { it.id to ReviewCache.digest("convention-rule-v1/$common/${it.copy(approved = false, rejected = false, decidedBy = null, review = null)}") }
    }

    internal fun apply(m: ConventionModel, response: String, hash: String, reviewer: String): ConventionModel {
        val opinion = ArchitectureReviewer.parseOpinion(response)
        val byId = opinion.rules.filter { it.verdict in ArchitectureReviewer.verdicts && it.reason.isNotBlank() }.associateBy { it.id }
        return m.copy(
            rules = m.rules.map { r -> byId[r.id]?.let { r.copy(review = RuleReview(it.verdict, it.reason)) } ?: r },
            review = ModelReview(if (ArchitectureReviewer.complete(opinion, m.rules.map { it.id })) hash else "", reviewer, opinion.missing, opinion.applicability),
        )
    }
}

object ConventionApproval {
    fun approve(m: ConventionModel, ids: Collection<String>) = update(m, ids) { it.copy(approved = true, rejected = false, decidedBy = "user") }
    fun reject(m: ConventionModel, ids: Collection<String>) = update(m, ids) { it.copy(approved = false, rejected = true, decidedBy = "user") }
    fun keepIds(m: ConventionModel) = m.rules.filter { it.review?.verdict == "KEEP" && !it.approved && !it.rejected }.map { it.id }
    fun autoApprove(m: ConventionModel): ConventionModel = m.copy(rules = m.rules.map {
        if (it.decidedBy != null && it.decidedBy != "ai-review") it
        else when (it.review?.verdict) {
            "KEEP" -> it.copy(approved = true, rejected = false, decidedBy = "ai-review")
            "SUSPECT" -> it.copy(approved = false, rejected = true, decidedBy = "ai-review")
            else -> it.copy(approved = false, rejected = false, decidedBy = null)
        }
    })

    fun keepUserDecisions(saved: ConventionModel?, m: ConventionModel): ConventionModel {
        val user = saved?.rules?.filter { it.decidedBy == "user" }?.associateBy { it.id }.orEmpty()
        return m.copy(rules = m.rules.map { r ->
            user[r.id]?.let { r.copy(approved = it.approved, rejected = it.rejected, decidedBy = it.decidedBy) } ?: r
        })
    }

    fun carryOver(previous: ConventionModel?, mined: ConventionModel): ConventionModel {
        if (previous == null) return mined
        return mined.copy(
            rules = mined.rules.map { r ->
                previous.rules.firstOrNull { it.id == r.id && it.kind == r.kind && it.params == r.params }
                    ?.let { o -> if (o.decidedBy == "auto" || o.decidedBy == "ai-review" || (r.decidedBy == "auto" && o.decidedBy != "user")) r.copy(review = o.review) else r.copy(approved = o.approved, rejected = o.rejected, decidedBy = o.decidedBy, review = o.review) } ?: r
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
        appendLine("규칙 후보 ${m.rules.size}개 (AI 검토 뒤 판정대로 승인·거절):")
        m.rules.forEach { appendLine("  - (${it.severity}) ${it.description}") }
        if (m.notes.isNotEmpty()) { appendLine(); appendLine("참고:"); m.notes.forEach { appendLine("  ! $it") } }
        if (selfCheck != null) {
            appendLine()
            appendLine("자기 검증(생성한 규칙을 현재 코드에 적용): 위반 ${selfCheck.size}건 (기존 코드의 예외)")
            selfCheck.take(10).forEach { appendLine("  ! ${it.ruleId} ${it.file}:${it.line} ${it.message}") }
        }
    }

    fun triage(m: ConventionModel, projectId: String): String = buildString {
        val auto = m.rules.filter { it.decidedBy == "ai-review" || it.decidedBy == "auto" }
        val user = m.rules.count { it.decidedBy == "user" }
        val pending = m.rules.filter { !it.approved && !it.rejected }
        appendLine("규칙 ${m.rules.size}개: 자동 승인 ${auto.count { it.approved }}(차단 ${auto.count { it.approved && it.severity == "BLOCK" }}) · 자동 거절 ${auto.count { it.rejected }} · 사용자 결정 ${user} · 판정 없음 ${pending.size}")
        appendLine("기존 코드가 이미 어기는 규칙은 검토로 낮춰 승인했습니다. 바꾸려면 approve·reject $projectId <id>")
        if (pending.isNotEmpty()) {
            appendLine("AI 판정이 없어 꺼 둔 규칙:")
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
