package com.dochiri.ilsim.runner

import com.dochiri.ilsim.runner.architecture.ArchitectureReviewer
import com.dochiri.ilsim.runner.architecture.RuleReview
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 개별 근거가 유효한 의견은 재사용하되, 바뀐 전체 근거의 누락·충돌 검토와 재판정 권한은 유지한다. */
object RuleReviewReuse {
    fun response(prompt: String, expected: List<String>, reused: Map<String, RuleReview>, ask: (String) -> String): String {
        if (reused.isEmpty()) return ask(prompt)
        val remaining = expected.filterNot { it in reused }
        val context = reused.map { (id, review) -> ArchitectureReviewer.RuleOpinion(id, review.verdict, review.reason) }
        DecisionScope.record("RULE_REVIEW", "CACHE", "개별 근거·의존 규칙이 유효한 의견 ${reused.size}개 재사용; 전역 검토 유지", prompt)
        val response = ask(prompt + "\n\n## 기존 의견 재사용\n" + Json.encodeToString(context) +
            "\n개별 재검토 대상은 $remaining 이다. 모든 전체 근거로 전역 누락·충돌·적용 범위를 다시 검토하라. 기존 의견도 다른 규칙의 근거와 충돌하거나 새 전역 문제가 있으면 rules에 포함해 수정하라. 그 외 기존 의견은 반복 출력하지 마라. missing과 applicability는 반드시 답하라.")
        val opinion = ArchitectureReviewer.parseOpinion(response)
        val returned = opinion.rules.map { it.id }
        require(opinion.completeFields && returned.distinct().size == returned.size && returned.all { it in expected } && returned.containsAll(remaining) &&
            opinion.rules.all { it.verdict in ArchitectureReviewer.verdicts && it.reason.isNotBlank() } && opinion.applicability?.isNotBlank() == true) { "부분 규칙·전역 검토가 불완전합니다" }
        val merged = context.associateBy { it.id } + opinion.rules.associateBy { it.id }
        return Json.encodeToString(opinion.copy(rules = expected.map { merged.getValue(it) }))
    }
}
