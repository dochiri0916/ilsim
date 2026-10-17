package com.dochiri.ilsim.runner.jev

import com.dochiri.ilsim.runner.document.DocPurpose
import com.dochiri.ilsim.runner.document.DocumentFile
import com.dochiri.ilsim.runner.document.DocumentRef
import com.dochiri.ilsim.runner.document.DocumentSelector
import com.dochiri.ilsim.runner.document.HeuristicDocumentSelector
import io.github.gudcks0305.jev.NoulQuestion

/**
 * 프로젝트 문서 중 이 목적에 쓸 문서를 JEV 로 고른다. 이름·경로·제목 키워드로 후보를 먼저 줄이고(정적),
 * 후보별로 "이 문서가 목적의 규칙을 설명하는가"를 한 번의 요청으로 묻는다. JEV 를 쓸 수 없으면 정적 선택으로 되돌아간다.
 */
class JevDocumentSelector(
    private val jev: Jev,
    private val fallback: DocumentSelector = HeuristicDocumentSelector(),
    private val threshold: Double = 0.5,
    private val limit: Int = 3,
) : DocumentSelector {

    private val candidates = HeuristicDocumentSelector(limit = 8, minScore = 1)

    override fun select(purpose: DocPurpose, candidates: List<DocumentFile>): List<DocumentRef> {
        if (!jev.enabled) return fallback.select(purpose, candidates)
        val shortlist = this.candidates.select(purpose, candidates)
        if (shortlist.isEmpty()) return emptyList()
        val byPath = candidates.associateBy { it.path }
        val state = mapOf(
            "purpose" to purpose.label,
            "criteria" to purpose.criteria,
            "documents" to shortlist.mapIndexed { i, r -> mapOf("id" to "d$i", "path" to r.path, "text" to byPath.getValue(r.path).text.take(800)) },
        )
        val questions = shortlist.indices.associate { i ->
            "d$i" to NoulQuestion.of("d$i", "문서 d$i 는 ${purpose.label} 규칙(criteria)을 직접 설명하는가?")
                .withCriteria("Yes, the document states rules about ${purpose.label}.", "No, the document is background, a guide, or about something else.")
        }
        val answers = jev.probabilities(state, questions) ?: return fallback.select(purpose, candidates)
        return shortlist.mapIndexedNotNull { i, r -> answers["d$i"]?.takeIf { it >= threshold }?.let { p -> DocumentRef(r.path, Math.round(p * 10).toInt(), "JEV 관련도 ${"%.2f".format(p)}") } }
            .sortedByDescending { it.score }.take(limit)
    }
}
