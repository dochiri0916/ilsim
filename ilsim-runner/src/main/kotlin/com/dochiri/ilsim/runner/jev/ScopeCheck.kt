package com.dochiri.ilsim.runner.jev

import io.github.gudcks0305.jev.NoulQuestion

object ScopeCheck {
    const val INSIDE = 0.8
    const val OUTSIDE = 0.2
    private const val MAX_SPEC_CHARS = 6_000
    private const val MAX_FILE_WEIGHT = 3_000
    private const val MAX_CHANGE_WEIGHT = 1_500
    private const val CONTEXT = 3
    private val trivial = Regex("""^[+-]\s*((import|package)\s.*)?$""")
    private val header = Regex("""^diff --git a/(\S+) b/(\S+)""")
    private val heading = Regex("""^(#{1,6})\s+(.*)$""")
    private val pathToken = Regex("""[A-Za-z0-9_.\-]+(?:/[A-Za-z0-9_.\-]+)+/?""")
    private val packageToken = Regex("""(?<![\w.])[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)+(?![\w])""")
    private val STAGES = mapOf(
        "TEST_CODE" to "테스트 코드 단계. 이 단계 변경은 요구사항과 테스트 케이스를 검증하는 테스트만 담는다.",
        "IMPLEMENTATION" to "구현 단계. 이 단계 변경은 요구사항을 만족시키는 프로덕트 코드와 설정이다. 테스트는 앞 단계에서 이미 작성됐다.",
    )

    data class Result(val probabilities: Map<String, Double>, val listed: List<String>, val outside: List<String>, val uncertain: List<String>)

    data class Change(val path: String, val diff: String, val changed: String)

    fun files(diff: String): Map<String, String> {
        val out = linkedMapOf<String, StringBuilder>()
        var current: StringBuilder? = null
        for (line in diff.lines()) {
            header.find(line)?.let { m -> current = out.getOrPut(m.groupValues[2]) { StringBuilder() } }
            current?.appendLine(line)
        }
        return out.mapValues { it.value.toString() }
    }

    fun changes(diff: String): List<Change> = files(diff).flatMap { (path, text) ->
        val out = mutableListOf<Change>()
        var header: String? = null
        val hunk = mutableListOf<String>()
        fun flush() {
            var i = 0
            while (i < hunk.size) {
                if (!edited(hunk[i])) { i++; continue }
                var end = i
                while (end < hunk.size && edited(hunk[end])) end++
                val body = hunk.subList(i, end)
                if (body.any { !trivial.matches(it) }) out += Change(path, (listOfNotNull(header) + hunk.subList(maxOf(0, i - CONTEXT), minOf(hunk.size, end + CONTEXT))).joinToString("\n"), body.joinToString("\n"))
                i = end
            }
            hunk.clear()
        }
        for (line in text.lines()) when {
            line.startsWith("@@") -> { flush(); header = line }
            header != null && !line.startsWith("\\") -> hunk += line
        }
        flush()
        out
    }

    private fun edited(line: String) = line.startsWith("+") || line.startsWith("-")

    fun scope(markdown: String): String? {
        val out = StringBuilder()
        var level = 0
        for (line in markdown.lines()) {
            val h = heading.matchEntire(line.trimEnd())
            if (h != null) {
                val depth = h.groupValues[1].length
                when {
                    "영향 범위" in h.groupValues[2] -> level = depth
                    level > 0 && depth <= level -> level = 0
                }
                continue
            }
            if (level > 0) out.appendLine(line)
        }
        return out.toString().trim().ifEmpty { null }
    }

    fun listed(spec: String, path: String): Boolean {
        val name = path.substringAfterLast('/')
        val base = name.substringBefore('.')
        if (path in spec || Regex("""(?<![\w.])${Regex.escape(name)}(?![\w])""").containsMatchIn(spec)) return true
        if (base.any(Char::isUpperCase) && Regex("""(?<![\w])${Regex.escape(base)}(?![\w])""").containsMatchIn(spec)) return true
        val section = scope(spec) ?: return false
        val dirs = pathToken.findAll(section).map { it.value.trimEnd('/') }.filter { '.' !in it.substringAfterLast('/') } +
            packageToken.findAll(section).map { it.value.replace('.', '/') }
        return dirs.any { d -> '/' in d && "/$path".contains("/$d/") }
    }

    fun judge(jev: Jev, spec: String, stage: String, diff: String): Result? {
        val all = files(diff)
        val listed = all.keys.filter { listed(spec, it) }
        val asked = all.filterKeys { it !in listed }
        if (asked.isEmpty()) return Result(emptyMap(), listed, emptyList(), emptyList())
        if (!jev.enabled) return null
        val probabilities = ask(jev, spec, stage, all.keys.toList(), asked) ?: return null
        val outside = asked.keys.filter { (probabilities[it] ?: 1.0) <= OUTSIDE }
        val uncertain = asked.keys.filter { it !in outside && (probabilities[it] ?: 0.0) < INSIDE }
        return Result(probabilities, listed, outside, uncertain)
    }

    internal fun question(i: Int, path: String): NoulQuestion =
        NoulQuestion.of("f$i", "변경 파일 f$i($path)의 변경이 spec(요구사항 문서)의 요구사항을 구현하거나 테스트하는 데 필요한 범위 안인가? 요구사항을 위해 새로 만드는 클래스·예외·설정·의존성과 그 요구사항을 검증하는 테스트는 Yes. 요구사항과 관계없는 다른 기능 수정, 관계없는 리팩터링·이름 변경·포맷 변경, 쓰지 않는 설정·의존성 변경, 관계없는 테스트 수정·삭제, spec 이 바꾸지 않는다고 한 기존 기능 변경이면 No.")
            .withCriteria("요구사항에 필요한 변경", "요구사항과 관계없는 변경")

    internal fun changeQuestion(i: Int, path: String): NoulQuestion =
        NoulQuestion.of("c$i", "변경 묶음 c$i($path)은 spec(요구사항 문서)의 요구사항을 구현하거나 테스트하는 데 필요한 변경인가? 앞이 + 또는 - 인 줄만 이번 변경이고 앞이 공백인 줄은 바뀌지 않은 기존 코드다. 요구사항을 위한 코드·도우미·설정·테스트와 그 때문에 꼭 필요한 기존 코드 수정은 Yes. 요구사항과 관계없는 다른 동작(다른 명령·입력·경우의 처리, 다른 규칙·검사 조건)을 바꾸거나, 기존 검사·검증·테스트를 줄이거나 지우거나, 관계없는 리팩터링·이름·포맷 변경이면 No.")
            .withCriteria("요구사항에 필요한 변경", "요구사항과 관계없는 변경")

    internal fun ask(jev: Jev, spec: String, stage: String, changed: List<String>, files: Map<String, String>): Map<String, Double>? =
        batched(jev, spec, stage, changed, files.toList(), "files", "f", MAX_FILE_WEIGHT, ::question)
            ?.let { p -> files.keys.zip(p).mapNotNull { (path, v) -> v?.let { path to it } }.toMap() }

    fun askChanges(jev: Jev, spec: String, stage: String, changed: List<String>, changes: List<Change>): List<Double?>? =
        batched(jev, spec, stage, changed, changes.map { it.path to it.diff }, "changes", "c", MAX_CHANGE_WEIGHT, ::changeQuestion)

    private fun batched(jev: Jev, spec: String, stage: String, changed: List<String>, items: List<Pair<String, String>>, key: String, prefix: String, limit: Int, question: (Int, String) -> NoulQuestion): List<Double?>? {
        val shownSpec = spec.take(MAX_SPEC_CHARS)
        val budget = Guidelines.MAX_STATE_WEIGHT - Guidelines.weight(shownSpec) - Guidelines.weight(changed.joinToString("\n")) - Guidelines.weight(STAGES[stage].orEmpty())
        val shown = items.map { (path, diff) -> path to Guidelines.cut(diff, limit) }
        val batches = mutableListOf<MutableList<Int>>()
        var used = 0
        shown.forEachIndexed { index, (path, diff) ->
            val size = Guidelines.weight(path) + Guidelines.weight(diff)
            if (batches.isEmpty() || batches.last().size >= Guidelines.BATCH || used + size > budget) { batches += mutableListOf<Int>(); used = 0 }
            batches.last() += index
            used += size
        }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(Guidelines.PARALLEL)
        try {
            val answers = batches.map { batch ->
                pool.submit<Map<Int, Double>?> {
                    val state = mapOf(
                        "stage" to "$stage: ${STAGES[stage].orEmpty()}",
                        "spec" to shownSpec,
                        "changed" to changed,
                        key to batch.mapIndexed { i, index -> mapOf("id" to "$prefix$i", "path" to shown[index].first, "diff" to shown[index].second) },
                    )
                    val p = jev.probabilities(state, batch.withIndex().associate { (i, index) -> "$prefix$i" to question(i, shown[index].first) }) ?: return@submit null
                    batch.withIndex().mapNotNull { (i, index) -> p["$prefix$i"]?.let { index to it } }.toMap()
                }
            }.map { it.get() ?: return null }.fold(emptyMap<Int, Double>()) { a, b -> a + b }
            return items.indices.map { answers[it] }
        } finally {
            pool.shutdownNow()
        }
    }
}
