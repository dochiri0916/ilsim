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
    const val APPLIES = 0.5
    const val BATCH = 10
    const val PARALLEL = 4
    const val MAX_REQUEST_CHARS = 4000
    const val MAX_STATE_WEIGHT = 22_000
    const val MAX_DIFF_WEIGHT = 12_000
    const val MAX_OUTLINE = 60
    const val MAX_START_CHARS = 1500

    data class Check(val probabilities: Map<Guideline, Double>, val violated: List<Guideline>, val uncertain: List<Guideline>, val truncated: Boolean)

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
        byDocument(sections, MAX_STATE_WEIGHT - weight(request.take(MAX_REQUEST_CHARS)), { it.path }, { weight(it.heading) + weight(it.text) }).map { batch -> pool.submit<List<Guideline>?> { selectBatch(jev, request, batch) } }
            .map { it.get() ?: return null }.flatten().associateBy { Triple(it.path, it.line, it.text) }

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
        val answers = asks.map { (g, targets) -> pool.submit<Map<String, Double>?> { requiredBatch(jev, request, g, targets) } }.map { it.get() ?: return null }
        val all = answers.flatMap { it.entries }
        val forbidden = all.filter { it.value < 0 }.map { it.key }.toSet()
        return all.filter { it.key in forbidden || it.value >= APPLIES }.groupBy({ it.key }, { it.value }).mapValues { (path, ps) -> if (path in forbidden) -1.0 else ps.max() }
    }

    private fun requiredBatch(jev: Jev, request: String, g: Guideline, targets: List<String>): Map<String, Double>? {
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
        return targets.withIndex().associate { (j, t) -> t to if ((answers["n$j"] ?: 0.0) >= VIOLATED) -1.0 else (answers["q$j"] ?: 0.0) }
    }

    fun documents(jev: Jev, request: String, sections: List<DocSection>): Map<String, Double>? {
        if (!jev.enabled) return null
        val pool = java.util.concurrent.Executors.newFixedThreadPool(PARALLEL)
        try { return documents(jev, request, sections, pool) } finally { pool.shutdownNow() }
    }

    private fun documents(jev: Jev, request: String, sections: List<DocSection>, pool: java.util.concurrent.ExecutorService): Map<String, Double>? {
        val batches = sections.groupBy { it.path }.entries.map { pool.submit<Map<String, Double>?> { documentBatch(jev, request, listOf(it.key to it.value)) } }
        return batches.map { it.get() ?: return null }.fold(emptyMap()) { a, b -> a + b }
    }

    private fun documentBatch(jev: Jev, request: String, batch: List<Pair<String, List<DocSection>>>): Map<String, Double>? {
        val state = mapOf(
            "request" to request.take(MAX_REQUEST_CHARS),
            "documents" to batch.mapIndexed { i, (path, parts) ->
                mapOf("id" to "d$i", "path" to path, "headings" to parts.map { it.heading }.distinct().take(MAX_OUTLINE), "start" to parts.first().text.take(MAX_START_CHARS))
            },
        )
        val questions = batch.indices.flatMap { i ->
            listOf(
                "k$i" to NoulQuestion.of("k$i", "문서 d$i 에 코드를 만들거나 고칠 때 따라야 할 규칙·스펙(정해진 동작, 구조, 의존 방향, 이름, 포맷, 테스트 방식, 작업 원칙, 금지 사항 등)을 담은 구간이 있는가? path, headings, start 로 판단한다. 작업 기록·인수인계 일지, 계획서, 분석·평가 보고서, 회의 메모, 변경 이력, 설치·배포 안내만 있고 따를 규칙 구간이 없으면 No. 그런 문서라도 작업 규칙 같은 규칙 구간이 따로 있으면 Yes.")
                    .withCriteria("따를 규칙·스펙 구간이 있는 문서", "기록·계획·분석·안내만 있는 문서"),
                "m$i" to NoulQuestion.of("m$i", "요청(request)이 만들거나 바꾸는 코드의 모듈·디렉터리에 문서 d$i 가 적용되는가? 저장소 전체나 모든 모듈에 적용되는 문서는 Yes. path 나 내용으로 보아 다른 모듈·하위 프로젝트 전용 문서이면 No.")
                    .withCriteria("요청이 건드리는 모듈에 적용됨", "다른 모듈 전용 문서"),
            )
        }.toMap()
        val answers = jev.probabilities(state, questions) ?: return null
        return batch.mapIndexed { i, (path, _) -> path to minOf(answers["k$i"] ?: 1.0, answers["m$i"] ?: 1.0) }.toMap()
    }

    private fun selectBatch(jev: Jev, request: String, batch: List<DocSection>): List<Guideline>? {
        val selected = mutableListOf<Guideline>()
        val state = mapOf(
            "request" to request.take(MAX_REQUEST_CHARS),
            "sections" to batch.mapIndexed { i, s -> mapOf("id" to "s$i", "source" to "${s.path}:${s.line}", "heading" to s.heading, "text" to s.text) },
        )
        val questions = batch.indices.flatMap { i ->
            listOf(
                "r$i" to NoulQuestion.of("r$i", "문서 구간 s$i 의 text 자체에 코드를 만들거나 고칠 때 따라야 할 구체적인 규칙·스펙(정해진 동작, 문구, 구조, 의존 방향, 이름, 포맷, 테스트 방식, 금지 사항 등)이 문장이나 표로 적혀 있는가? 소개, 배경, 목적, 개요, 미결정 사항·질문, 제외 목록, 일정, 변경 이력, 연락처, 설치·빌드·실행·배포 안내처럼 지킬 규칙이 없는 구간이면 No. 규칙이 합리적인지는 따지지 않는다.")
                    .withCriteria("따라야 할 구체적 규칙이 적혀 있음", "지킬 규칙이 없는 설명·안내 구간"),
                "s$i" to NoulQuestion.of("s$i", "요청(request)을 이 프로젝트에서 구현하거나 테스트할 때 새로 만들거나 바꾸는 코드에 문서 구간 s$i 의 규칙이 적용되는가? 프로젝트 전체에 적용되는 규칙은 Yes. 특정 기능 하나만의 요구사항·테스트 케이스는 요청이 그 기능 자체를 바꿀 때만 Yes이고, 다른 기능을 새로 추가하는 요청이면 No.")
                    .withCriteria("요청 작업 코드에 적용됨", "요청 작업 코드에 적용되지 않음"),
            )
        }.toMap()
        val answers = jev.probabilities(state, questions) ?: return null
        batch.forEachIndexed { i, s -> selected += Guideline(s.path, s.line, s.heading, s.text, minOf(answers["r$i"] ?: 1.0, answers["s$i"] ?: 1.0)) }
        return selected
    }

    private const val TASK_CONTEXT = "일심(ilsim) MCP가 요구사항·테스트 케이스·테스트 코드·구현 단계로 나눠 Agent에게 맡기는 단계 작업이다. 프롬프트가 일심 단계 지시다."
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

    fun check(jev: Jev, stage: String, guidelines: List<Guideline>, diff: String): Check? {
        if (!jev.enabled) return null
        val shown = cut(diff, MAX_DIFF_WEIGHT)
        val truncated = shown.length < diff.length
        val pool = java.util.concurrent.Executors.newFixedThreadPool(PARALLEL)
        val probabilities = try {
            byDocument(guidelines.sortedBy { it.line }, MAX_STATE_WEIGHT - weight(shown), { it.path }, { weight(it.heading) + weight(it.text) }).map { batch -> pool.submit<Map<Guideline, Double>?> { checkBatch(jev, stage, batch, shown) } }
                .map { it.get() ?: return null }.fold(emptyMap<Guideline, Double>()) { a, b -> a + b }
        } finally {
            pool.shutdownNow()
        }
        val violated = guidelines.filter { (probabilities[it] ?: 0.0) >= VIOLATED }
        val uncertain = guidelines.filter { it !in violated && (truncated || (it.relevance >= APPLIES && (probabilities[it] ?: 1.0) > CLEAR)) }
        return Check(probabilities, violated, uncertain, truncated)
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

    private fun checkBatch(jev: Jev, stage: String, batch: List<Guideline>, diff: String): Map<Guideline, Double>? {
        val state = mapOf(
            "stage" to stage,
            "guidelines" to batch.mapIndexed { i, g -> mapOf("id" to "g$i", "source" to g.source, "heading" to g.heading, "text" to g.text) },
            "diff" to diff,
        )
        val questions = batch.indices.associate { i ->
            "v$i" to NoulQuestion.of("v$i", "변경(diff)이 지침 g$i 의 text 에 적힌 규칙을 직접 어기는가? g$i 의 text 에 적힌 규칙만 기준으로 하고, 다른 지침의 위반은 g$i 위반으로 보지 않는다. diff 에 g$i 가 정한 것과 다른 구조·이름·문구·동작·의존·테스트 방식이 있으면 Yes. g$i 에 지킬 규칙이 없거나(소개·배경·미결정 사항 등), 규칙이 이 변경 코드에 적용되지 않거나, 지키고 있으면 No. 이 단계(stage)에서 아직 만들 차례가 아닌 코드가 없는 것은 위반이 아니다. 지침이 합리적인지나 더 나은 방법이 있는지는 따지지 않고 적힌 대로만 판정한다.")
                .withCriteria("변경이 지침을 어김", "변경이 지침을 지키거나 지침과 무관함")
        }
        val answers = jev.probabilities(state, questions) ?: return null
        return batch.withIndex().mapNotNull { (i, g) -> answers["v$i"]?.let { g to it } }.toMap()
    }
}
