package com.dochiri.ilsim.runner.jev

import io.github.gudcks0305.jev.NoulQuestion

object TestCodeCheck {
    const val COVERED = 0.8
    const val MISSING = 0.2
    const val BYPASSED = 0.8
    const val CLEAN = 0.35
    private const val MAX_CASE_CHARS = 400
    private const val STAGE = "테스트 코드 단계. diff는 이번 단계에서 작성한 테스트 코드다. 구현은 다음 단계에서 하므로 테스트가 지금 실패하는 것은 정상이다."
    private val row = Regex("""^\s*\|\s*(TC-\d+)\s*\|(.*)$""")
    private val bullet = Regex("""^\s*(?:[-*+]|\d+[.)])\s+\**(TC-\d+)\**\b(.*)$""")
    private val staticMark = Regex("""정적\s*검사|정적\s*확인|코드\s*확인""")
    private val regressionMark = Regex("""회귀""")
    private val existingRun = Regex("""기존[^|]*테스트|테스트[^|]*(?:실행|그대로)|gradlew|빌드""")

    data class TestCase(val id: String, val text: String) {
        val static: Boolean get() = staticMark.containsMatchIn(text)
        val regression: Boolean get() = regressionMark.containsMatchIn(text) && existingRun.containsMatchIn(text)
        val exempt: Boolean get() = static || regression
    }

    data class Result(
        val coverage: Map<String, Double>,
        val missing: List<TestCase>,
        val unsureCases: List<TestCase>,
        val bypass: Map<String, Double>,
        val bypassed: List<String>,
        val unsureFiles: List<String>,
        val truncated: Boolean,
    )

    fun cases(markdown: String): List<TestCase> = markdown.lines().mapNotNull { line ->
        (row.matchEntire(line) ?: bullet.matchEntire(line))?.let { m ->
            TestCase(m.groupValues[1], m.groupValues[2].replace(Regex("""\s*\|\s*"""), " | ").trim(' ', '|').replace(Regex("""\s+"""), " ").take(MAX_CASE_CHARS))
        }
    }.distinctBy { it.id }

    fun judge(jev: Jev, cases: List<TestCase>, diff: String): Result? {
        if (!jev.enabled) return null
        val shown = Guidelines.cut(diff, Guidelines.MAX_DIFF_WEIGHT)
        val testable = cases.filterNot { it.exempt }
        val coverage = if (testable.isEmpty()) emptyMap() else coverage(jev, testable, shown) ?: return null
        val files = ScopeCheck.files(diff).filterKeys { com.dochiri.ilsim.runner.Gate.isTestPath(it) }
        val bypass = if (files.isEmpty()) emptyMap() else bypass(jev, cases, files) ?: return null
        val truncated = shown.length < diff.length
        val missing = if (truncated) emptyList() else testable.filter { (coverage[it.id] ?: 1.0) <= MISSING }
        val unsureCases = testable.filter { it !in missing && (coverage[it.id] ?: 0.0) < COVERED }
        val bypassed = files.keys.filter { (bypass[it] ?: 0.0) >= BYPASSED }
        val unsureFiles = files.keys.filter { it !in bypassed && (bypass[it] ?: 1.0) > CLEAN }
        return Result(coverage, missing, unsureCases, bypass, bypassed, unsureFiles, truncated)
    }

    internal fun coverageQuestion(i: Int, case: TestCase): NoulQuestion =
        NoulQuestion.of("t$i", "diff의 테스트 코드에 테스트 케이스 ${case.id}「${case.text}」의 시나리오를 실행하는 테스트가 있는가? 같은 입력·조건으로 그 동작을 호출하는 테스트가 있으면 Yes(테스트 이름에 TC 번호가 없어도 됨). 단언이 약하거나 꺼 둔 테스트인지는 따지지 않는다. 그 시나리오(입력·조건·대상 동작)를 다루는 테스트가 diff에 없으면 No.")
            .withCriteria("TC 시나리오를 다루는 테스트가 있음", "TC 시나리오를 다루는 테스트가 없음")

    private val BYPASS_KINDS = listOf(
        "환경 변수·시각·브랜치 같은 조건에 따라 일찍 return하거나 건너뛰는 테스트, assume·skip·@Disabled·xit 로 꺼 둔 테스트",
        "단언이 하나도 없거나 단언이 if 같은 조건문 안에만 있어 조건이 거짓이면 아무것도 확인하지 않는 테스트",
        "예외가 나야 하는 동작을 try로 감싸고 fail() 없이 catch 안에서만 확인해, 예외가 나지 않으면 그냥 통과하는 테스트",
        "검증 대상 클래스 자체를 목·스텁으로 바꾸고 그 목이 돌려주도록 정한 값을 단정하는 테스트, 또는 어떤 결과든 통과하는 느슨한 단언(0 이상, null 아님, 존재함, 참)만으로 TC의 구체적인 기대 결과를 확인하지 않는 테스트",
    )

    internal fun bypassQuestion(i: Int, k: Int, path: String): NoulQuestion =
        NoulQuestion.of("b${i}_$k", "테스트 파일 f$i($path)의 + 줄에 다음과 같은 테스트가 있는가? ${BYPASS_KINDS[k]}. 있으면 Yes, 없으면 No. 구현이 아직 없어 지금 실패하는 것은 문제가 아니다.")
            .withCriteria("그런 테스트가 있음", "그런 테스트가 없음")

    internal fun coverage(jev: Jev, cases: List<TestCase>, diff: String): Map<String, Double>? = parallel(cases.chunked(Guidelines.BATCH)) { batch ->
        val state = mapOf("stage" to STAGE, "testcases" to batch.map { mapOf("id" to it.id, "text" to it.text) }, "diff" to diff)
        val answers = jev.probabilities(state, batch.withIndex().associate { (i, c) -> "t$i" to coverageQuestion(i, c) }) ?: return@parallel null
        batch.withIndex().mapNotNull { (i, c) -> answers["t$i"]?.let { c.id to it } }.toMap()
    }

    internal fun bypass(jev: Jev, cases: List<TestCase>, files: Map<String, String>): Map<String, Double>? {
        val caseText = cases.joinToString("\n") { "${it.id} ${it.text}" }
        val budget = Guidelines.MAX_STATE_WEIGHT - Guidelines.weight(STAGE) - Guidelines.weight(caseText)
        val shown = files.mapValues { Guidelines.cut(it.value, Guidelines.MAX_DIFF_WEIGHT) }
        val batches = mutableListOf<MutableList<String>>()
        var used = 0
        for ((path, text) in shown) {
            val size = Guidelines.weight(path) + Guidelines.weight(text)
            if (batches.isEmpty() || batches.last().size >= Guidelines.BATCH || used + size > budget) { batches += mutableListOf<String>(); used = 0 }
            batches.last() += path
            used += size
        }
        return parallel(batches) { batch ->
            val state = mapOf("stage" to STAGE, "testcases" to caseText, "files" to batch.mapIndexed { i, path -> mapOf("id" to "f$i", "path" to path, "diff" to shown.getValue(path)) })
            val answers = jev.probabilities(state, batch.withIndex().flatMap { (i, path) -> BYPASS_KINDS.indices.map { k -> "b${i}_$k" to bypassQuestion(i, k, path) } }.toMap()) ?: return@parallel null
            batch.withIndex().mapNotNull { (i, path) -> BYPASS_KINDS.indices.mapNotNull { k -> answers["b${i}_$k"] }.maxOrNull()?.let { path to it } }.toMap()
        }
    }

    private fun <T> parallel(batches: List<T>, ask: (T) -> Map<String, Double>?): Map<String, Double>? {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(Guidelines.PARALLEL)
        try {
            return batches.map { batch -> pool.submit<Map<String, Double>?> { ask(batch) } }.map { it.get() ?: return null }.fold(emptyMap()) { a, b -> a + b }
        } finally {
            pool.shutdownNow()
        }
    }
}
