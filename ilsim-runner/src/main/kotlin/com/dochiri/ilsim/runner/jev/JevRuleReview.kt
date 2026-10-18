package com.dochiri.ilsim.runner.jev

import com.dochiri.ilsim.runner.DecisionScope
import io.github.gudcks0305.jev.NoulQuestion
import kotlinx.serialization.json.*

/** 규칙별 판정과 전역 누락 검토를 분리한다. 품질 비교를 통과한 환경에서만 JEV 완료를 허용한다. */
object JevRuleReview {
    data class Answer(val text: String, val reviewer: String)

    fun review(
        prompt: String, rules: List<Pair<String, String>>, jev: Jev = Jev.fromEnvironment(),
        enabled: Boolean = System.getenv("ILSIM_JEV_RULE_COMPLETION") == "true", ask: (String) -> String,
    ): Answer {
        if (!enabled || !jev.enabled || rules.isEmpty()) return Answer(ask(prompt), "agent")
        val questions = rules.flatMapIndexed { i, (id, _) -> listOf(
            "keep$i" to NoulQuestion.of("keep$i", "규칙 $id 는 관측과 프로젝트 문서가 뒷받침하는 의도적인 기존 규칙인가?"),
            "enough$i" to NoulQuestion.of("enough$i", "규칙 $id 의 KEEP/SUSPECT를 제공된 근거만으로 확정할 수 있는가? 추가 코드·문서가 필요하면 No."),
        ) }.toMap() + ("gap" to NoulQuestion.of("gap", "후보에 없는 규칙을 문서에서 새로 찾거나 적용 범위를 추가로 설명해야 하는가? 근거가 불충분하면 Yes."))
        val answers = jev.probabilities(mapOf("policy" to "rule-completion-v1", "reviewInput" to prompt), questions)
            ?: return Answer(ask(prompt), "agent")
        val resolved = linkedMapOf<String, JsonObject>()
        rules.forEachIndexed { i, (id, evidence) ->
            val p = answers["keep$i"] ?: return@forEachIndexed
            if ((answers["enough$i"] ?: 0.0) < 0.97 || p > 0.03 && p < 0.97) return@forEachIndexed
            resolved[id] = buildJsonObject {
                put("id", id); put("verdict", if (p >= 0.97) "KEEP" else "SUSPECT")
                put("reason", "JEV 근거 충족, KEEP 확률 $p. $evidence")
            }
        }
        val remaining = rules.map { it.first }.filterNot { it in resolved }
        val gap = answers["gap"] ?: 1.0
        if (remaining.isEmpty() && gap <= 0.03) {
            DecisionScope.record("RULE_REVIEW", "JEV", "모든 후보 판정·근거와 전역 누락 검토 완료", prompt, "rule-completion-v1")
            return Answer(buildJsonObject {
                put("rules", JsonArray(resolved.values.toList())); put("missing", JsonArray(emptyList()))
                put("applicability", "JEV가 후보별 근거와 전역 검토 범위의 충족을 확인했습니다")
            }.toString(), "jev")
        }
        DecisionScope.record("RULE_REVIEW", "AGENT", "JEV 미해결 후보 ${remaining.size}개와 전역 누락·적용 범위 검토 이관", prompt, "rule-completion-v1")
        val response = ask(prompt + "\n\n## JEV 판단의 이관\n완료한 후보: ${resolved.keys}. 반복 판정하지 마라. 미해결 후보 $remaining 와 전역 누락·적용 범위만 검토하라. rules에는 미해결 후보만 포함하라. 기존 전체 근거는 전역 검토에 사용하라.\n" + JsonArray(resolved.values.toList()))
        val start = response.indexOf('{'); val end = response.lastIndexOf('}')
        require(start in 0 until end) { "규칙 검토 JSON 누락" }
        val obj = Json.parseToJsonElement(response.substring(start, end + 1)).jsonObject
        val returned = obj["rules"]?.jsonArray ?: error("규칙 검토 후보 누락")
        require(returned.size == remaining.size && returned.map { it.jsonObject["id"]?.jsonPrimitive?.content }.toSet() == remaining.toSet()) { "미해결 후보 검토가 불완전합니다" }
        require(obj["missing"] is JsonArray && obj["applicability"]?.jsonPrimitive?.content?.isNotBlank() == true) { "전역 검토가 불완전합니다" }
        return Answer(JsonObject(obj + ("rules" to JsonArray(resolved.values + returned))).toString(), "agent")
    }
}
