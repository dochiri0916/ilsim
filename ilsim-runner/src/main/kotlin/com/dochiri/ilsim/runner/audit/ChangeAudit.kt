package com.dochiri.ilsim.runner.audit

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object ChangeAudit {
    private const val DIFF_LIMIT = 24_000
    private const val SPEC_LIMIT = 6_000
    private const val BRIEF_LIMIT = 3_000
    private const val TESTS_LIMIT = 10_000
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    data class Finding(val type: String, val target: String = "", val reason: String = "")

    @Serializable
    data class Result(val verdict: String, val findings: List<Finding>)

    private val types = setOf("OMISSION", "UNRELATED", "TEST_BYPASS", "RULE", "WEAK_TEST")

    fun fixable(r: Result, stage: String = "IMPLEMENTATION"): List<Finding> = r.findings.filter {
        it.type != "WEAK_TEST" && !(stage == "TEST_CODE" && it.type == "OMISSION" && "TC-" !in it.target)
    }

    fun prompt(stage: String, requirements: String?, testSpec: String?, rulesBrief: String?, validation: Map<String, String>, diff: String, survivors: List<String> = emptyList(), committedTests: Map<String, String> = emptyMap()): String = buildString {
        appendLine("당신은 AI Agent가 만든 변경을 독립적으로 검토하는 리뷰어다. 정적 검사(아키텍처, 컨벤션, 위험, 테스트 무력화)는 이미 통과했다.")
        appendLine("설계가 좋은지 나쁜지는 판단하지 마라. 아래 네 가지만 찾아라. 근거가 diff 에서 확인되는 것만 보고하고, 추측은 하지 마라.")
        if (stage == "TEST_CODE") {
            appendLine("- OMISSION: 테스트 케이스(TC-n) 중 이 변경의 테스트로 작성되지 않은 것 (단계: $stage)")
            appendLine("  (이 단계는 테스트만 작성하고 프로덕션 코드는 고치지 않는다. 구현이 없어 테스트가 실패하거나 컴파일되지 않는 것은 정상이니 지적하지 마라)")
        } else appendLine("- OMISSION: 요구사항(REQ-n)이나 테스트 케이스(TC-n) 중 이 변경에서 다뤄지지 않은 것 (단계: $stage)")
        if (committedTests.isNotEmpty()) appendLine("  (테스트는 이전 단계에서 승인·커밋되어 diff 에 없다. 아래 '기존 테스트'에 있으면 다뤄진 것이다. 이 단계에서는 구현이 요구사항을 만족하는지만 본다)")
        appendLine("- UNRELATED: 요구사항과 무관한 변경(불필요한 수정, 삭제, 포맷만 바꾼 대량 변경)")
        appendLine("- TEST_BYPASS: 테스트를 약화하거나 우회하는 변경(단언 완화, 기대값 변경, 조건부 통과, 목으로 핵심 로직 대체)")
        appendLine("- RULE: 아래 프로젝트 규칙을 어겼지만 정적 검사가 못 잡았을 것으로 보이는 변경")
        if (survivors.isNotEmpty()) appendLine("- WEAK_TEST: 아래 '살아남은 변이' 중 요구사항상 중요한 동작인데 테스트가 검증하지 못하는 것 (등가 변이는 보고하지 마라)")
        appendLine("JSON 만 출력하라(설명·코드펜스 금지): {\"verdict\":\"PASS|CONCERN\",\"findings\":[{\"type\":\"OMISSION|UNRELATED|TEST_BYPASS|RULE|WEAK_TEST\",\"target\":\"REQ-1 또는 파일:줄\",\"reason\":\"한 문장\"}]}")
        appendLine("문제가 없으면 {\"verdict\":\"PASS\",\"findings\":[]} 로 답하라.")
        requirements?.let { appendLine(); appendLine("## 요구사항"); appendLine(it) }
        testSpec?.let { appendLine(); appendLine("## 테스트 케이스"); appendLine(it) }
        if (committedTests.isNotEmpty()) {
            appendLine(); appendLine("## 기존 테스트 (이전 단계에서 커밋됨, 이 변경에 포함되지 않음)")
            committedTests.forEach { (path, text) ->
                appendLine("### $path"); appendLine(text)
            }
        }
        rulesBrief?.let { appendLine(); appendLine(it) }
        appendLine()
        appendLine("## 정적 검사 결과")
        validation.forEach { (k, v) -> appendLine("- $k: $v") }
        if (survivors.isNotEmpty()) {
            appendLine()
            appendLine("## 살아남은 변이 (변이 테스트에서 테스트가 잡지 못한 것, 상위 ${survivors.size}건)")
            survivors.forEach { appendLine("- $it") }
        }
        appendLine()
        appendLine("## 변경 diff (전체 ${diff.length}자)")
        appendLine(diff)
    }

    fun parse(response: String): Result {
        val start = response.indexOf('{')
        val end = response.lastIndexOf('}')
        require(start in 0 until end) { "AI 응답에서 JSON 을 찾지 못했습니다" }
        val raw = try {
            json.decodeFromString<Result>(response.substring(start, end + 1))
        } catch (e: Exception) {
            throw IllegalArgumentException("AI 응답을 해석하지 못했습니다: ${e.message}")
        }
        require(raw.verdict in setOf("PASS", "CONCERN")) { "AI 검토 판정이 유효하지 않습니다" }
        require(raw.findings.all { it.type in types && it.reason.isNotBlank() && it.target.isNotBlank() }) { "AI 검토 지적의 필수 필드가 누락되었습니다" }
        require(raw.verdict != "CONCERN" || raw.findings.isNotEmpty()) { "CONCERN에는 근거가 필요합니다" }
        return Result(if (raw.findings.isEmpty()) "PASS" else "CONCERN", raw.findings)
    }

    fun render(r: Result): String = buildString {
        appendLine("# AI 변경 검토")
        if (r.findings.isEmpty()) appendLine("\n지적 사항 없음")
        r.findings.forEach { appendLine("- [${it.type}] ${it.target.ifBlank { "-" }}: ${it.reason}") }
    }
}
