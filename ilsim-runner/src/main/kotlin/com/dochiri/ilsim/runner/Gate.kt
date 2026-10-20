package com.dochiri.ilsim.runner

object Gate {

    private val testPathPatterns = listOf(
        Regex("""(^|/)(src/test|tests?|__tests__)/"""),
        Regex("""(Test|Tests|IT|Spec)\.(java|kt|ts|tsx|js|py|groovy)$"""),
        Regex("""\.(test|spec)\.(ts|tsx|js|jsx)$"""),
    )

    private val ignoredPrefixes = listOf("build/", "target/", "node_modules/", ".gradle/", "__pycache__/", ".idea/")

    fun isTestPath(path: String): Boolean = testPathPatterns.any { it.containsMatchIn(path) }

    fun isIgnored(path: String): Boolean =
        ignoredPrefixes.any { path.startsWith(it) || path.contains("/$it") } || path.endsWith(".pyc")

    fun requirementsPath(feature: String) = "specs/$feature/requirements.md"

    fun testSpecPath(feature: String) = "specs/$feature/test-spec.md"

    fun pathViolations(stage: String, feature: String, changed: List<String>): List<String> {
        if (changed.isEmpty()) return listOf("변경된 파일이 없습니다")
        return when (stage) {
            "REQUIREMENTS" -> changed.filter { it != requirementsPath(feature) }
                .map { "허용되지 않은 변경: $it (${requirementsPath(feature)}만 수정 가능)" }
            "TEST_CASES" -> changed.filter { it != testSpecPath(feature) }
                .map { "허용되지 않은 변경: $it (${testSpecPath(feature)}만 수정 가능)" }
            "TEST_CODE" -> changed.filterNot(::isTestPath)
                .map { "테스트 경로가 아닌 변경: $it" }
            "IMPLEMENTATION" -> changed.filter { isTestPath(it) || it.startsWith("specs/") }
                .map { "수정 금지 경로 변경: $it (테스트와 specs는 구현 단계에서 수정할 수 없음)" }
            else -> listOf("알 수 없는 단계: $stage")
        }
    }

    fun traceabilityViolations(requirements: String, testSpec: String): List<String> {
        val reqs = Regex("""REQ-\d+""").findAll(requirements).map { it.value }.toSortedSet()
        if (reqs.isEmpty()) return listOf("요구사항 문서에 REQ-n 항목이 없습니다")
        val out = mutableListOf<String>()
        if (!Regex("""TC-\d+""").containsMatchIn(testSpec)) out += "테스트 케이스 문서에 TC-n 항목이 없습니다"
        val covered = Regex("""REQ-\d+""").findAll(testSpec).map { it.value }.toSet()
        reqs.filterNot(covered::contains).forEach { out += "$it 에 연결된 테스트 케이스가 없습니다" }
        return out
    }

    const val ANSWERED_MARK = "[ILSIM:ANSWERED]"

    fun allowedTools(stage: String, testCommand: String? = null): String = when (stage) {
        "REQUIREMENTS", "TEST_CASES" -> "Read,Glob,Grep,Write,Edit"
        else -> "Read,Glob,Grep,Write,Edit,Bash(./gradlew:*),Bash(python3:*),Bash(git status:*),Bash(git diff:*)" +
            (testCommand?.trim()?.takeIf { it.isNotEmpty() && it.none { c -> c in ",()" } }?.let { ",Bash($it:*)" } ?: "")
    }

    fun selfCheck(stage: String, testCommand: String): String? = when (stage) {
        "TEST_CODE" -> """
            ## 직접 확인
            끝내기 전에 `$testCommand`를 실행하라. 새 테스트가 컴파일되고, 구현이 없어서 실패(red)해야 한다.
            컴파일 오류나 테스트 자체의 실수는 고치고 다시 실행하라. 구현이 없는데 통과하는 테스트는 요구사항을 검증하도록 고쳐라.
            """.trimIndent()
        "IMPLEMENTATION" -> """
            ## 직접 확인
            끝내기 전에 `$testCommand`를 실행해 모든 테스트가 통과하는지 확인하라. 실패하면 원인을 파악해 고치고 다시 실행하라.
            끝내 통과시키지 못하면 어떤 테스트가 왜 실패하는지, 사용자가 무엇을 정해야 하는지 설명하고 끝내라.
            """.trimIndent()
        else -> null
    }
}
