package com.dochiri.ilsim.runner.jev

import com.dochiri.ilsim.runner.document.DocSection
import io.github.gudcks0305.jev.NoulQuestion
import kotlinx.serialization.Serializable

@Serializable
data class Guideline(val path: String, val line: Int, val heading: String, val text: String, val relevance: Double) {
    val source: String get() = "$path:$line"
}

object Guidelines {
    const val IRRELEVANT = 0.2
    const val VIOLATED = 0.8
    const val CLEAR = 0.4
    const val UNSURE = 0.4
    const val LEGACY = 0.6
    const val APPLIES = 0.5
    const val BATCH = 10
    const val PARALLEL = 4
    const val MAX_REQUEST_CHARS = 4000
    const val MAX_STATE_WEIGHT = 22_000
    const val PERMITTED = 0.8
    private const val MAX_PERMISSION_TEXT = 600
    const val MAX_DIFF_WEIGHT = 12_000
    const val MAX_OUTLINE = 60
    const val MAX_START_CHARS = 1500

    data class Check(val probabilities: Map<Guideline, Double>, val violated: List<Guideline>, val uncertain: List<Guideline>, val truncated: Boolean, val parts: List<Part> = emptyList(), val blocking: Map<Guideline, Double> = probabilities)

    data class Part(val guideline: Guideline, val text: String, val batch: Double?, val single: Double?, val before: Double? = null, val added: Double? = null) {
        val raw: Double? get() = listOfNotNull(batch, single).maxOrNull()
        val legacy: Boolean get() = (before ?: 0.0) >= LEGACY && (added ?: 1.0) <= CLEAR
        val probability: Double? get() = if (legacy) added else raw
        val agreed: Double? get() = if (legacy) added else listOfNotNull(batch, single).minOrNull()
    }

    data class Selection(val guidelines: List<Guideline>, val filtered: Map<String, Double>)

    data class DocumentScore(val path: String, val relevance: Double, val selected: Int, val reinstated: Boolean)

    fun select(jev: Jev, request: String, sections: List<DocSection>, modules: Set<String> = emptySet()): List<Guideline>? = selection(jev, request, sections, modules)?.guidelines

    fun selection(jev: Jev, request: String, sections: List<DocSection>, modules: Set<String> = emptySet()): Selection? {
        if (!jev.enabled) return null
        val pool = java.util.concurrent.Executors.newFixedThreadPool(PARALLEL)
        try {
            val touched = touched(jev, request, modules) ?: return null
            val filtered = (documents(jev, request, sections, pool) ?: return null).mapValues { (path, p) ->
                touched.entries.firstOrNull { path.startsWith("${it.key}/") }?.let { minOf(p, it.value) } ?: p
            }
            val docScores = filtered.toMutableMap()
            val first = score(jev, request, sections.filter { (docScores[it.path] ?: 1.0) > IRRELEVANT }, pool) ?: return null
            fun capped(g: Guideline) = g.copy(relevance = minOf(g.relevance, docScores[g.path] ?: 1.0))
            val required = required(jev, request, first.values.map(::capped).filter { it.relevance > IRRELEVANT }, sections.map { it.path }.toSet(), pool) ?: return null
            required.forEach { (path, p) -> docScores[path] = if (p < 0) 0.0 else maxOf(docScores[path] ?: 0.0, p) }
            val scored = first + (score(jev, request, sections.filter { (required[it.path] ?: 0.0) > 0 && key(it) !in first }, pool) ?: return null)
            return Selection(sections.map { s -> scored[key(s)]?.let(::capped) ?: Guideline(s.path, s.line, s.heading, s.text, docScores[s.path] ?: 0.0) }, filtered)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun key(s: DocSection) = Triple(s.path, s.line, s.text)

    fun touched(jev: Jev, request: String, modules: Set<String>): Map<String, Double>? {
        if (modules.size < 2) return emptyMap()
        val list = modules.sorted()
        val state = mapOf("request" to request.take(MAX_REQUEST_CHARS), "modules" to list.mapIndexed { i, m -> mapOf("id" to "m$i", "path" to "$m/") })
        val questions = list.indices.associate { i ->
            "w$i" to NoulQuestion.of("w$i", "요청(request)을 처리하려면 모듈 m$i(path 디렉터리) 안의 코드·문서를 만들거나 바꾸는가? 요청이 그 모듈 이름이나 그 모듈의 기능을 다루면 Yes. 다른 모듈만 다루면 No.")
                .withCriteria("이 모듈을 바꾸는 요청", "다른 모듈을 바꾸는 요청")
        }
        val answers = jev.probabilities(state, questions) ?: return null
        return list.withIndex().associate { (i, m) -> m to (answers["w$i"] ?: 1.0) }
    }

    private fun score(jev: Jev, request: String, sections: List<DocSection>, pool: java.util.concurrent.ExecutorService): Map<Triple<String, Int, String>, Guideline>? =
        sectionAnswers(jev, request, sections, pool)?.entries?.associate { (s, rs) -> key(s) to Guideline(s.path, s.line, s.heading, s.text, minOf(rs.first, rs.second)) }

    internal fun sectionAnswers(jev: Jev, request: String, sections: List<DocSection>, pool: java.util.concurrent.ExecutorService): Map<DocSection, Pair<Double, Double>>? =
        byDocument(sections, MAX_STATE_WEIGHT - weight(request.take(MAX_REQUEST_CHARS)), { it.path }, { weight(it.heading) + weight(it.text) })
            .map { batch -> pool.submit<Map<DocSection, Pair<Double, Double>>?> { sectionBatch(jev, request, batch) } }
            .map { it.get() ?: return null }.fold(emptyMap()) { a, b -> a + b }

    fun links(source: String, text: String, paths: Set<String>): List<String> {
        val parent = source.substringBeforeLast('/', "")
        return (MD_LINK.findAll(text).map { it.groupValues[1] } + CODE_PATH.findAll(text).map { it.groupValues[1] })
            .flatMap { link -> listOf(if (parent.isEmpty()) link else "$parent/$link", link) }
            .mapNotNull { runCatching { java.nio.file.Path.of(it).normalize().toString().replace('\\', '/') }.getOrNull() }
            .filter { it in paths && it != source }
            .distinct().toList()
    }

    private fun required(jev: Jev, request: String, selected: List<Guideline>, paths: Set<String>, pool: java.util.concurrent.ExecutorService): Map<String, Double>? {
        val asks = selected.map { it to links(it.path, it.text, paths) }.filter { it.second.isNotEmpty() }
        val answers = asks.map { (g, targets) -> pool.submit<Map<String, Pair<Double, Double>>?> { referenceAnswers(jev, request, g, targets) } }.map { it.get() ?: return null }
        val all = answers.flatMap { it.entries }.map { (path, qn) -> path to if (qn.second >= VIOLATED) -1.0 else qn.first }
        val forbidden = all.filter { it.second < 0 }.map { it.first }.toSet()
        return all.filter { it.first in forbidden || it.second >= APPLIES }.groupBy({ it.first }, { it.second }).mapValues { (path, ps) -> if (path in forbidden) -1.0 else ps.max() }
    }

    internal fun referenceAnswers(jev: Jev, request: String, g: Guideline, targets: List<String>): Map<String, Pair<Double, Double>>? {
        val state = mapOf(
            "request" to request.take(MAX_REQUEST_CHARS),
            "task" to TASK_CONTEXT,
            "section" to mapOf("source" to g.source, "heading" to g.heading, "text" to g.text),
            "targets" to targets.mapIndexed { j, t -> mapOf("id" to "t$j", "path" to t) },
        )
        val questions = targets.indices.associate { j ->
            "q$j" to NoulQuestion.of("q$j", "구간(section)의 text 가 요청(request) 작업을 할 때 문서 t$j 를 읽거나 따르라고 요구하는가? 특정 모듈·작업에만 해당하는 요구는 요청이 그 모듈·작업일 때만 Yes. 요청 작업에서는 읽지 말라고 하거나, 단순 참고·목차·출처 링크이면 No.")
                .withCriteria("요청 작업에서 그 문서를 읽거나 따라야 함", "요구하지 않거나 단순 참고 링크")
        } + targets.indices.associate { j ->
            "n$j" to NoulQuestion.of("n$j", "구간(section)의 text 가 이 작업(task)과 같은 종류의 작업에서는 문서 t$j 를 읽거나 갱신하지 말라고 하는가?")
                .withCriteria("이 작업에서는 그 문서를 읽지 말라고 함", "그런 금지가 없음")
        }
        val answers = jev.probabilities(state, questions) ?: return null
        return targets.withIndex().associate { (j, t) -> t to ((answers["q$j"] ?: 0.0) to (answers["n$j"] ?: 0.0)) }
    }

    fun documents(jev: Jev, request: String, sections: List<DocSection>): Map<String, Double>? {
        if (!jev.enabled) return null
        val pool = java.util.concurrent.Executors.newFixedThreadPool(PARALLEL)
        try { return documents(jev, request, sections, pool) } finally { pool.shutdownNow() }
    }

    private fun documents(jev: Jev, request: String, sections: List<DocSection>, pool: java.util.concurrent.ExecutorService): Map<String, Double>? =
        documentAnswers(jev, request, sections, pool)?.mapValues { minOf(it.value.first, it.value.second) }

    internal fun documentAnswers(jev: Jev, request: String, sections: List<DocSection>, pool: java.util.concurrent.ExecutorService): Map<String, Pair<Double, Double>>? {
        val batches = sections.groupBy { it.path }.entries.map { pool.submit<Map<String, Pair<Double, Double>>?> { documentBatch(jev, request, listOf(it.key to it.value)) } }
        return batches.map { it.get() ?: return null }.fold(emptyMap()) { a, b -> a + b }
    }

    private fun documentBatch(jev: Jev, request: String, batch: List<Pair<String, List<DocSection>>>): Map<String, Pair<Double, Double>>? {
        val state = mapOf(
            "request" to request.take(MAX_REQUEST_CHARS),
            "documents" to batch.mapIndexed { i, (path, parts) ->
                mapOf("id" to "d$i", "path" to path, "headings" to parts.map { it.heading }.distinct().take(MAX_OUTLINE), "start" to parts.first().text.take(MAX_START_CHARS))
            },
        )
        val questions = batch.indices.flatMap { i ->
            listOf(
                "k$i" to ruleDocument(i),
                "m$i" to NoulQuestion.of("m$i", "요청(request)이 만들거나 바꾸는 코드의 모듈·디렉터리에 문서 d$i 가 적용되는가? 저장소 전체나 모든 모듈에 적용되는 문서는 Yes. path 나 내용으로 보아 다른 모듈·하위 프로젝트 전용 문서이면 No.")
                    .withCriteria("요청이 건드리는 모듈에 적용됨", "다른 모듈 전용 문서"),
            )
        }.toMap()
        val answers = jev.probabilities(state, questions) ?: return null
        return batch.mapIndexed { i, (path, _) -> path to ((answers["k$i"] ?: 1.0) to (answers["m$i"] ?: 1.0)) }.toMap()
    }

    private fun ruleDocument(i: Int): NoulQuestion =
        NoulQuestion.of("k$i", "문서 d$i 에 코드를 만들거나 고칠 때 따라야 할 규칙·스펙(정해진 동작, 구조, 의존 방향, 이름, 포맷, 테스트 방식, 작업 원칙, 금지 사항 등)을 담은 구간이 있는가? path, headings, start 로 판단한다. 작업 기록·인수인계 일지, 계획서, 분석·평가 보고서, 회의 메모, 변경 이력, 설치·배포 안내만 있고 따를 규칙 구간이 없으면 No. 그런 문서라도 작업 규칙 같은 규칙 구간이 따로 있으면 Yes.")
            .withCriteria("따를 규칙·스펙 구간이 있는 문서", "기록·계획·분석·안내만 있는 문서")

    private fun ruleSection(i: Int): NoulQuestion =
        NoulQuestion.of("r$i", "문서 구간 s$i 의 text 자체에 코드를 만들거나 고칠 때 따라야 할 구체적인 규칙·스펙(정해진 동작, 문구, 구조, 의존 방향, 이름, 포맷, 테스트 방식, 금지 사항 등)이 문장이나 표로 적혀 있는가? 소개, 배경, 목적, 개요, 미결정 사항·질문, 제외 목록, 일정, 변경 이력, 연락처, 설치·빌드·실행·배포 안내처럼 지킬 규칙이 없는 구간이면 No. 규칙이 합리적인지는 따지지 않는다.")
            .withCriteria("따라야 할 구체적 규칙이 적혀 있음", "지킬 규칙이 없는 설명·안내 구간")

    fun ruleScores(jev: Jev, sections: List<DocSection>): Map<DocSection, Double>? {
        if (!jev.enabled) return null
        val pool = java.util.concurrent.Executors.newFixedThreadPool(PARALLEL)
        try {
            val documents = sections.groupBy { it.path }.entries.map { (path, parts) -> pool.submit<Pair<String, Double>?> { ruleDocumentScore(jev, path, parts) } }
                .map { it.get() ?: return null }.toMap()
            val candidates = sections.filter { (documents[it.path] ?: 1.0) > IRRELEVANT }
            val scored = byDocument(candidates, MAX_STATE_WEIGHT, { it.path }, { weight(it.heading) + weight(it.text) })
                .map { batch -> pool.submit<Map<DocSection, Double>?> { ruleSectionScores(jev, batch) } }
                .map { it.get() ?: return null }.fold(emptyMap<DocSection, Double>()) { a, b -> a + b }
            return sections.associateWith { minOf(documents[it.path] ?: 1.0, scored[it] ?: 0.0) }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun ruleDocumentScore(jev: Jev, path: String, parts: List<DocSection>): Pair<String, Double>? {
        val state = mapOf("documents" to listOf(mapOf("id" to "d0", "path" to path, "headings" to parts.map { it.heading }.distinct().take(MAX_OUTLINE), "start" to parts.first().text.take(MAX_START_CHARS))))
        val answers = jev.probabilities(state, mapOf("k0" to ruleDocument(0))) ?: return null
        return path to (answers["k0"] ?: 1.0)
    }

    private fun ruleSectionScores(jev: Jev, batch: List<DocSection>): Map<DocSection, Double>? {
        val state = mapOf("sections" to batch.mapIndexed { i, s -> mapOf("id" to "s$i", "source" to "${s.path}:${s.line}", "heading" to s.heading, "text" to s.text) })
        val answers = jev.probabilities(state, batch.indices.associate { "r$it" to ruleSection(it) }) ?: return null
        return batch.withIndex().associate { (i, s) -> s to (answers["r$i"] ?: 1.0) }
    }

    private fun sectionBatch(jev: Jev, request: String, batch: List<DocSection>): Map<DocSection, Pair<Double, Double>>? {
        val state = mapOf(
            "request" to request.take(MAX_REQUEST_CHARS),
            "sections" to batch.mapIndexed { i, s -> mapOf("id" to "s$i", "source" to "${s.path}:${s.line}", "heading" to s.heading, "text" to s.text) },
        )
        val questions = batch.indices.flatMap { i ->
            listOf(
                "r$i" to ruleSection(i),
                "s$i" to NoulQuestion.of("s$i", "요청(request)을 이 프로젝트에서 구현하거나 테스트할 때 새로 만들거나 바꾸는 코드에 문서 구간 s$i 의 규칙이 적용되는가? 프로젝트 전체에 적용되는 규칙은 Yes. 특정 기능 하나만의 요구사항·테스트 케이스는 요청이 그 기능 자체를 바꿀 때만 Yes이고, 다른 기능을 새로 추가하는 요청이면 No.")
                    .withCriteria("요청 작업 코드에 적용됨", "요청 작업 코드에 적용되지 않음"),
            )
        }.toMap()
        val answers = jev.probabilities(state, questions) ?: return null
        return batch.withIndex().associate { (i, s) -> s to ((answers["r$i"] ?: 1.0) to (answers["s$i"] ?: 1.0)) }
    }

    private const val TASK_CONTEXT = "일심(ilsim) MCP가 요구사항·테스트 케이스·테스트 코드·구현 단계로 나눠 Agent에게 맡기는 단계 작업이다. 프롬프트가 일심 단계 지시다."
    private val STAGES = mapOf(
        "REQUIREMENTS" to "요구사항 문서를 쓰는 단계. diff는 요구사항 문서이고 코드는 아직 없다.",
        "TEST_CASES" to "테스트 케이스 문서를 쓰는 단계. diff는 테스트 케이스 문서이고 코드는 아직 없다.",
        "TEST_CODE" to "테스트 코드를 쓰는 단계. diff는 테스트 코드만 담고 프로덕트 코드는 다음 구현 단계에서 만든다. 어떤 테스트를 쓸지는 앞 단계에서 승인된 테스트 케이스로 정해졌으므로, 지침이 말하는 종류의 테스트를 새로 더 쓰지 않은 것은 위반이 아니다. 단, diff에 있는 테스트 파일의 위치·실행 방식·이름·단언·데이터 준비가 지침과 다르면 위반이다.",
        "IMPLEMENTATION" to "구현 단계. diff는 프로덕트 코드만 담는다. 테스트는 앞 단계(테스트 코드 단계)에서 이미 작성돼 이 diff에 없다.",
    )
    private val MD_LINK = Regex("""\]\(([^)\s#]+\.(?:md|markdown|adoc|rst))(?:#[^)]*)?\)""")
    private val CODE_PATH = Regex("""`([^`\s]+\.(?:md|markdown|adoc|rst))`""")

    fun relevant(scored: List<Guideline>): List<Guideline> = scored.filter { it.relevance > IRRELEVANT }.sortedByDescending { it.relevance }

    fun documentScores(scored: List<Guideline>, filtered: Map<String, Double>): List<DocumentScore> =
        scored.groupBy { it.path }.map { (path, parts) ->
            val selected = parts.count { it.relevance > IRRELEVANT }
            DocumentScore(path, parts.maxOf { it.relevance }, selected, selected > 0 && (filtered[path] ?: 1.0) <= IRRELEVANT)
        }.sortedWith(compareByDescending<DocumentScore> { it.relevance }.thenBy { it.path })

    fun documentLine(d: DocumentScore): String =
        "  ${if (d.selected > 0) "+" else "-"} ${"%.2f".format(d.relevance)} 구간 ${d.selected}개 ${d.path}${if (d.reinstated) " 참조" else ""}"

    fun scores(scored: List<Guideline>): List<String> = scored.map { "${it.source}=${"%.2f".format(it.relevance)}" }

    fun check(jev: Jev, stage: String, guidelines: List<Guideline>, diff: String, decisions: List<String> = emptyList()): Check? {
        if (!jev.enabled) return null
        val shown = cut(diff, MAX_DIFF_WEIGHT)
        val truncated = shown.length < diff.length
        val units = guidelines.sortedBy { it.line }.flatMap { g -> items(g.text).map { g to it } }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(PARALLEL)
        val parts = try {
            val first = byDocument(units, MAX_STATE_WEIGHT - weight(shown), { it.first.path }, { weight(it.first.heading) + weight(it.second) })
                .map { batch -> batch to pool.submit<List<Double?>?> { checkBatch(jev, stage, batch, shown, decisions) } }
                .flatMap { (batch, answers) -> batch.zip(answers.get() ?: return null) }
            val again = first.map { (unit, p) -> if (p != null && p >= IRRELEVANT && p < VIOLATED) pool.submit<Double?> { checkBatch(jev, stage, listOf(unit), shown, decisions)?.single() } else null }
            val scored = first.zip(again).map { (scored, single) -> Part(scored.first.first, scored.first.second, scored.second, single?.get()) }
            val flagged = scored.filter { (it.raw ?: 0.0) > CLEAR }.map { it.guideline to it.text }
            val previous = previous(shown)
            if (flagged.isEmpty() || previous == null) scored else {
                val before = judgeAll(jev, stage, flagged, previous, decisions, pool) ?: return null
                val added = judgeAll(jev, stage, flagged, additions(shown), decisions, pool) ?: return null
                scored.map { p -> if ((p.raw ?: 0.0) > CLEAR) p.copy(before = before[p.guideline to p.text], added = added[p.guideline to p.text]) else p }
            }
        } finally {
            pool.shutdownNow()
        }
        val probabilities = guidelines.mapNotNull { g -> parts.filter { it.guideline == g }.mapNotNull { it.probability }.maxOrNull()?.let { g to it } }.toMap()
        val blocking = guidelines.mapNotNull { g -> parts.filter { it.guideline == g }.mapNotNull { it.agreed }.maxOrNull()?.let { g to it } }.toMap()
        val violated = guidelines.filter { (blocking[it] ?: 0.0) >= VIOLATED }
        val uncertain = guidelines.filter { it !in violated && (truncated || (it.relevance >= APPLIES && (probabilities[it] ?: 1.0) > UNSURE)) }
        return Check(probabilities, violated, uncertain, truncated, parts, blocking)
    }

    private fun judgeAll(jev: Jev, stage: String, units: List<Pair<Guideline, String>>, view: String, decisions: List<String>, pool: java.util.concurrent.ExecutorService): Map<Pair<Guideline, String>, Double?>? =
        byDocument(units, MAX_STATE_WEIGHT - weight(view), { it.first.path }, { weight(it.first.heading) + weight(it.second) })
            .map { batch -> batch to pool.submit<List<Double?>?> { checkBatch(jev, stage, batch, view, decisions) } }
            .flatMap { (batch, answers) -> batch.zip(answers.get() ?: return null) }.toMap()

    private val header = Regex("""^(diff --git |index |--- |\+\+\+ |@@|new file mode|deleted file mode)""")

    internal fun previous(diff: String): String? {
        val lines = diff.lines().mapNotNull { line ->
            when {
                header.containsMatchIn(line) -> line
                line.startsWith("+") -> null
                line.startsWith(" ") || line.startsWith("-") -> "+" + line.drop(1)
                else -> null
            }
        }
        return lines.takeIf { ls -> ls.any { it.startsWith("+") && !it.startsWith("+++ ") } }?.joinToString("\n")
    }

    internal fun additions(diff: String): String = diff.lines().filter { header.containsMatchIn(it) || it.startsWith("+") }.joinToString("\n")

    private val bullet = Regex("""^([-*+]|\d+[.)])\s+""")
    private val headingLine = Regex("""^(#{1,6}|={1,6})\s+""")
    private val tableRule = Regex("""^\|?\s*:?-{3,}""")

    private class Block(val kind: Char, val lines: MutableList<String>) {
        val text: String get() = lines.joinToString("\n").trim()
    }

    internal fun items(text: String): List<String> {
        val blocks = blocks(text)
        val out = mutableListOf<String>()
        var lead: String? = null
        blocks.forEachIndexed { i, b ->
            when {
                b.kind == 'L' -> out += listOfNotNull(lead, b.text).joinToString("\n")
                b.kind == 'T' -> {
                    val rows = b.lines
                    val data = if (rows.size > 2 && tableRule.containsMatchIn(rows[1])) rows.drop(2).map { rows[0] + "\n" + it } else rows
                    data.forEach { out += listOfNotNull(lead, it).joinToString("\n") }
                }
                blocks.getOrNull(i + 1)?.kind in setOf('L', 'T') -> lead = b.text
                else -> { lead = null; out += b.text }
            }
        }
        return out.takeIf { it.size >= 2 } ?: listOf(text)
    }

    private fun blocks(text: String): List<Block> {
        val lines = text.lines().let { if (it.isNotEmpty() && headingLine.containsMatchIn(it.first().trim())) it.drop(1) else it }
        val out = mutableListOf<Block>()
        var current: Block? = null
        var fenced = false
        for (line in lines) {
            val trimmed = line.trim()
            val fence = trimmed.startsWith("```") || trimmed.startsWith("~~~")
            if (fenced || fence) {
                if (fence) fenced = !fenced
                val target = current ?: out.lastOrNull() ?: Block('P', mutableListOf()).also(out::add)
                target.lines += line
                current = target
                continue
            }
            if (trimmed.isEmpty()) { current = null; continue }
            val indent = line.length - line.trimStart().length
            val parent = current ?: out.lastOrNull()?.takeIf { it.kind == 'L' }
            when {
                bullet.containsMatchIn(trimmed) && indent >= 2 && parent?.kind == 'L' -> { parent.lines += line; current = parent }
                bullet.containsMatchIn(trimmed) -> current = Block('L', mutableListOf(line)).also(out::add)
                trimmed.startsWith("|") -> {
                    if (current?.kind != 'T') current = Block('T', mutableListOf()).also(out::add)
                    current!!.lines += trimmed
                }
                current != null && current.kind != 'T' -> current.lines += line
                else -> current = Block('P', mutableListOf(line)).also(out::add)
            }
        }
        return out.filter { it.text.isNotEmpty() }
    }

    private fun <T> byDocument(items: List<T>, budget: Int, path: (T) -> String, size: (T) -> Int): List<List<T>> = items.groupBy(path).values.flatMap { group ->
        val batches = mutableListOf<MutableList<T>>()
        var used = 0
        for (item in group) {
            if (batches.isEmpty() || batches.last().size >= BATCH || used + size(item) > budget) { batches += mutableListOf<T>(); used = 0 }
            batches.last() += item
            used += size(item)
        }
        batches
    }

    fun weight(text: String): Int = text.sumOf { if (it.code < 128) 1 else 2 } / 2

    fun cut(text: String, budget: Int): String {
        var used = 0
        text.forEachIndexed { i, c ->
            used += if (c.code < 128) 1 else 2
            if (used / 2 > budget) return text.take(i)
        }
        return text
    }

    fun permitted(jev: Jev, guidelines: List<Guideline>, decisions: List<String>): Map<Guideline, Double>? {
        if (guidelines.isEmpty() || decisions.isEmpty()) return emptyMap()
        val state = mapOf(
            "decided" to decisions,
            "guidelines" to guidelines.mapIndexed { i, g -> mapOf("id" to "g$i", "source" to g.source, "heading" to g.heading, "text" to g.text.take(MAX_PERMISSION_TEXT)) },
        )
        val questions = guidelines.indices.associate { i ->
            "x$i" to NoulQuestion.of("x$i", "decided(사용자가 직접 정한 사항) 중에 지침 g$i 의 규칙과 다르게 해도 된다고 허락하거나, g$i 가 정한 것과 다른 동작·구조·방식을 하라고 정한 항목이 있는가? 있으면 Yes. decided 가 g$i 와 관계없거나 g$i 를 따르는 내용뿐이면 No.")
                .withCriteria("사용자가 이 지침과 다르게 하도록 정함", "사용자 결정이 이 지침과 관계없음")
        }
        val answers = jev.probabilities(state, questions) ?: return null
        return guidelines.withIndex().mapNotNull { (i, g) -> answers["x$i"]?.let { g to it } }.toMap()
    }

    private fun checkBatch(jev: Jev, stage: String, batch: List<Pair<Guideline, String>>, diff: String, decisions: List<String> = emptyList()): List<Double?>? {
        val state = listOfNotNull(
            ("decided" to decisions).takeIf { decisions.isNotEmpty() },
        ).toMap() + mapOf(
            "task" to TASK_CONTEXT,
            "stage" to "$stage: ${STAGES[stage].orEmpty()}",
            "guidelines" to batch.mapIndexed { i, (g, text) -> mapOf("id" to "g$i", "source" to g.source, "heading" to g.heading, "text" to text) },
            "diff" to diff,
        )
        val questions = batch.indices.associate { i ->
            "v$i" to NoulQuestion.of("v$i", "변경(diff)이 지침 g$i 의 text 에 적힌 규칙을 직접 어기는가? g$i 의 text 에 적힌 규칙만 기준으로 하고, 다른 지침의 위반은 g$i 위반으로 보지 않는다. diff 에 g$i 가 정한 것과 다른 구조·이름·문구·동작·의존·테스트 방식이 있으면 Yes. g$i 에 지킬 규칙이 없거나(소개·배경·미결정 사항 등), 규칙이 이 변경 코드에 적용되지 않거나, 지키고 있으면 No. 이 단계(stage)에서 아직 만들 차례가 아닌 코드나 앞 단계에서 이미 만든 코드(구현 단계 전에 테스트 코드 단계에서 작성한 테스트 등)가 이 diff에 없는 것은 위반이 아니다. 지침이 합리적인지나 더 나은 방법이 있는지는 따지지 않고 적힌 대로만 판정한다.${if (decisions.isEmpty()) "" else " 단, decided(사용자가 승인 뒤 정한 사항과 사용자가 승인한 테스트 케이스)가 이 변경의 그 부분을 지침과 다르게 해도 된다고 명시했거나, 승인된 테스트 케이스가 그 동작이나 테스트 방식을 그대로 정했으면 위반이 아니다."}")
                .withCriteria("변경이 지침을 어김", "변경이 지침을 지키거나 지침과 무관함")
        }
        val answers = jev.probabilities(state, questions) ?: return null
        return batch.indices.map { answers["v$it"] }
    }
}
