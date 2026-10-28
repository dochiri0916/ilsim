package com.dochiri.ilsim.runner.jev

import io.github.gudcks0305.jev.NoulQuestion

object ScopeCheck {
    const val INSIDE = 0.8
    const val OUTSIDE = 0.2
    private const val MAX_SPEC_CHARS = 6_000
    private const val MAX_FILE_WEIGHT = 3_000
    private val header = Regex("""^diff --git a/(\S+) b/(\S+)""")
    private val heading = Regex("""^(#{1,6})\s+(.*)$""")
    private val pathToken = Regex("""[A-Za-z0-9_.\-]+(?:/[A-Za-z0-9_.\-]+)+/?""")
    private val packageToken = Regex("""(?<![\w.])[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)+(?![\w])""")
    private val STAGES = mapOf(
        "TEST_CODE" to "테스트 코드 단계. 이 단계 변경은 요구사항과 테스트 케이스를 검증하는 테스트만 담는다.",
        "IMPLEMENTATION" to "구현 단계. 이 단계 변경은 요구사항을 만족시키는 프로덕트 코드와 설정이다. 테스트는 앞 단계에서 이미 작성됐다.",
    )

    data class Result(val probabilities: Map<String, Double>, val listed: List<String>, val outside: List<String>, val uncertain: List<String>)

    fun files(diff: String): Map<String, String> {
        val out = linkedMapOf<String, StringBuilder>()
        var current: StringBuilder? = null
        for (line in diff.lines()) {
            header.find(line)?.let { m -> current = out.getOrPut(m.groupValues[2]) { StringBuilder() } }
            current?.appendLine(line)
        }
        return out.mapValues { it.value.toString() }
    }

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

    internal fun ask(jev: Jev, spec: String, stage: String, changed: List<String>, files: Map<String, String>): Map<String, Double>? {
        val shownSpec = spec.take(MAX_SPEC_CHARS)
        val budget = Guidelines.MAX_STATE_WEIGHT - Guidelines.weight(shownSpec) - Guidelines.weight(changed.joinToString("\n")) - Guidelines.weight(STAGES[stage].orEmpty())
        val shown = files.mapValues { Guidelines.cut(it.value, MAX_FILE_WEIGHT) }
        val batches = mutableListOf<MutableList<String>>()
        var used = 0
        for ((path, diff) in shown) {
            val size = Guidelines.weight(path) + Guidelines.weight(diff)
            if (batches.isEmpty() || batches.last().size >= Guidelines.BATCH || used + size > budget) { batches += mutableListOf<String>(); used = 0 }
            batches.last() += path
            used += size
        }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(Guidelines.PARALLEL)
        try {
            return batches.map { batch ->
                pool.submit<Map<String, Double>?> {
                    val state = mapOf(
                        "stage" to "$stage: ${STAGES[stage].orEmpty()}",
                        "spec" to shownSpec,
                        "changed" to changed,
                        "files" to batch.mapIndexed { i, path -> mapOf("id" to "f$i", "path" to path, "diff" to shown.getValue(path)) },
                    )
                    val answers = jev.probabilities(state, batch.withIndex().associate { (i, path) -> "f$i" to question(i, path) }) ?: return@submit null
                    batch.withIndex().mapNotNull { (i, path) -> answers["f$i"]?.let { path to it } }.toMap()
                }
            }.map { it.get() ?: return null }.fold(emptyMap()) { a, b -> a + b }
        } finally {
            pool.shutdownNow()
        }
    }
}
