package com.dochiri.ilsim.runner

/** 단계별 변경 허용 범위와 산출물 검사. Claude의 판단이 아니라 Runner가 diff로 직접 강제한다. */
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

    /** 변경된 파일이 단계의 허용 범위를 벗어나면 위반 사유 목록을 돌려준다. 비어 있으면 통과. */
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

    /** 요구사항의 모든 REQ-n 이 테스트 케이스 문서에 연결되어 있는지 확인한다. */
    fun traceabilityViolations(requirements: String, testSpec: String): List<String> {
        val reqs = Regex("""REQ-\d+""").findAll(requirements).map { it.value }.toSortedSet()
        if (reqs.isEmpty()) return listOf("요구사항 문서에 REQ-n 항목이 없습니다")
        val out = mutableListOf<String>()
        if (!Regex("""TC-\d+""").containsMatchIn(testSpec)) out += "테스트 케이스 문서에 TC-n 항목이 없습니다"
        val covered = Regex("""REQ-\d+""").findAll(testSpec).map { it.value }.toSet()
        reqs.filterNot(covered::contains).forEach { out += "$it 에 연결된 테스트 케이스가 없습니다" }
        return out
    }

    /** 요구사항 단계에서 Claude 가 질문에 답만 했다는 표시. 서버 PromptTemplates.ANSWERED_MARK 와 같아야 한다. */
    const val ANSWERED_MARK = "[ILSIM:ANSWERED]"

    /** 코드를 쓰는 단계는 프로젝트 테스트 명령도 허용한다. 도구 목록을 깨뜨릴 수 있는 문자(쉼표·괄호)가 있으면 넣지 않는다. */
    fun allowedTools(stage: String, testCommand: String? = null): String = when (stage) {
        "REQUIREMENTS", "TEST_CASES" -> "Read,Glob,Grep,Write,Edit"
        else -> "Read,Glob,Grep,Write,Edit,Bash(./gradlew:*),Bash(python3:*),Bash(git status:*),Bash(git diff:*)" +
            (testCommand?.trim()?.takeIf { it.isNotEmpty() && it.none { c -> c in ",()" } }?.let { ",Bash($it:*)" } ?: "")
    }

    /** Claude 가 끝내기 전에 직접 테스트를 돌려 보고 결과를 설명하게 하는 지시. Runner 는 끝난 뒤 같은 명령으로 다시 검증한다. */
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
