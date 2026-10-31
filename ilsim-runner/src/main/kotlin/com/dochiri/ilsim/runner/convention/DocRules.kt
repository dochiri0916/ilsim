package com.dochiri.ilsim.runner.convention

import com.dochiri.ilsim.runner.ReviewCache
import com.dochiri.ilsim.runner.architecture.Evidence
import com.dochiri.ilsim.runner.architecture.Introduced
import com.dochiri.ilsim.runner.architecture.RuleReview
import com.dochiri.ilsim.runner.architecture.Violation
import com.dochiri.ilsim.runner.document.DocSection
import com.dochiri.ilsim.runner.jev.Guidelines
import com.dochiri.ilsim.runner.jev.Jev
import io.github.gudcks0305.jev.NoulQuestion
import java.nio.file.Path
import kotlin.io.path.readLines
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

object DocRules {
    const val PREFIX = "doc:"
    const val FAITHFUL = 0.4
    const val MAX_PROMPT_CHARS = 24_000
    private const val MAX_DIRECTORIES = 60
    private const val RECHECK_FROM = 0.15
    private const val RECHECK_UNTIL = 0.6
    private const val VERSION = "doc-rules-v5"
    private const val MAX_REMAINING = 20
    private const val MISCONVERTED_DIVISOR = 5
    private const val MAX_REPAIRS = 5

    class Extracted(val rules: List<ConventionRule>, val documents: List<DocCoverage>, val notes: List<String>, val attempted: List<String> = emptyList())

    @Serializable
    data class RuleConflict(val kept: String, val dropped: String, val reason: String)

    @Serializable
    data class DocRuleModel(
        val rules: List<ConventionRule> = emptyList(),
        val documents: List<DocCoverage> = emptyList(),
        val notes: List<String> = emptyList(),
        val files: List<String> = emptyList(),
        val applicable: Boolean = true,
        val frozen: Map<String, Int> = emptyMap(),
        val attempted: List<String> = emptyList(),
        val conflicts: List<RuleConflict> = emptyList(),
    )

    internal class Proposal(val section: DocSection, val kind: String, val params: Map<String, List<String>>, val description: String, val quote: String)

    internal class SectionResult(val section: DocSection, val hash: String, val proposals: List<Proposal>, val remaining: List<String>)

    fun isDocRule(id: String) = id.startsWith(PREFIX)

    fun sectionHash(s: DocSection): String = ReviewCache.digest("$VERSION|${s.heading}|${s.text}")

    internal fun applicable(buildTool: String, facts: Facts): Boolean = facts.files.isNotEmpty() || buildTool == "maven" || buildTool.startsWith("gradle")

    internal fun extract(sections: List<DocSection>, facts: Facts, previous: DocRuleModel?, jev: Jev, ask: (String) -> String): Extracted {
        val kept = previous?.rules.orEmpty()
        val known = previous?.documents.orEmpty()
        val scores = Guidelines.ruleScores(jev, sections)
            ?: return Extracted(kept.map { evidence(it, facts) }, known, listOf("JEV를 쓸 수 없어 문서 규칙을 새로 뽑지 않았습니다. 이전 문서 규칙을 그대로 둡니다"))
        val notes = mutableListOf<String>()
        val rules = linkedMapOf<String, ConventionRule>()
        val coverage = mutableListOf<DocCoverage>()
        val pending = mutableListOf<Pair<DocSection, String>>()
        val tree = structure(facts)
        for (s in sections.filter { (scores[it] ?: 0.0) >= Guidelines.CLEAR }) {
            val hash = sectionHash(s)
            val cached = known.firstOrNull { it.hash == hash }
            if (cached == null) { pending += s to hash; continue }
            coverage += cached.copy(path = s.path, line = s.line, heading = s.heading)
            kept.filter { it.id in cached.rules }.forEach { r -> rules.putIfAbsent(r.id, r.copy(origin = r.origin?.copy(path = s.path, line = s.line, heading = s.heading))) }
        }
        val static = pending.associate { (s, _) -> s to StaticDocRules.extract(s) }
        val forAi = pending.filterNot { (s, _) -> static.getValue(s).let { it.proposals.isNotEmpty() && it.uncovered.isEmpty() } && s.text.lines().none { it.trimStart().startsWith("|") } }
        val asked = batches(forAi).flatMap { batch ->
            try {
                parse(ask(prompt(batch.map { it.first }, tree, batch.map { static.getValue(it.first).proposals.map { p -> p.quote } })), batch, notes)
            } catch (e: Exception) {
                notes += "문서 규칙 추출 실패(구간 ${batch.size}개, 다음 분석 때 다시 시도): ${e.message}"
                emptyList()
            }
        }.associateBy { it.section }
        if (pending.isNotEmpty()) notes += "문서 규칙 정적 추출: 구간 ${pending.size}개 중 ${pending.size - forAi.size}개는 정적으로만 옮김, 정적 규칙 ${static.values.sumOf { it.proposals.size }}개, AI에 넘긴 구간 ${forAi.size}개"
        val results = pending.mapNotNull { (s, hash) ->
            val fromAi = asked[s]
            val fixed = static.getValue(s).proposals
            if (fromAi == null && s in forAi.map { it.first }) null
            else SectionResult(s, hash, fixed + fromAi?.proposals.orEmpty(), fromAi?.remaining.orEmpty())
        }
        val faith = verify(jev, results.flatMap { it.proposals }, tree)
        if (faith == null) notes += "JEV 원문 일치 확인에 실패했습니다. 확인하지 못한 규칙은 의견 없이 제안합니다"
        for (result in results) {
            val remaining = result.remaining.toMutableList()
            val ids = result.proposals.mapNotNull { p ->
                val score = faith?.get(p)
                if (score != null && score < FAITHFUL) {
                    remaining += p.quote
                    notes += "${p.section.path}:${p.section.line} 원문과 다르다고 판정돼 정적 규칙에서 뺐습니다(${"%.2f".format(score)}): ${p.description}"
                    return@mapNotNull null
                }
                val rule = rule(p, score, result.hash)
                rules.putIfAbsent(rule.id, rule)
                rule.id
            }.distinct()
            coverage += DocCoverage(result.section.path, result.section.line, result.section.heading, result.hash, ids, remaining.distinct())
        }
        val extracted = rules.values.map { it.kind to it.params }.toSet()
        val twins = rules.values.map(::twin).toSet()
        val carried = kept.filter { old ->
            old.id !in rules && (old.kind to old.params) !in extracted && (twin(old) !in twins || old.decidedBy == "user") && old.origin?.let { o -> sections.any { it.path == o.path && quoted(it.text, o.quote) } } == true
        }.sortedByDescending { it.decidedBy == "user" }.distinctBy(::twin)
        if (carried.isNotEmpty()) notes += "다시 뽑을 때 빠졌지만 원문이 그대로인 이전 문서 규칙 ${carried.size}개를 이어받았습니다"
        carried.forEach { rules[it.id] = it }
        val winners = rules.values.groupBy(::twin).mapValues { (_, same) -> same.firstOrNull { it.decidedBy == "user" } ?: same.first() }
        val replaced = rules.values.associate { it.id to winners.getValue(twin(it)).id }
        val dropped = replaced.count { (id, winner) -> id != winner }
        if (dropped > 0) notes += "같은 문장에서 같은 종류로 뽑힌 문서 규칙 ${dropped}개를 하나로 합쳤습니다"
        val merged = coverage.map { c -> c.copy(rules = c.rules.map { replaced[it] ?: it }.distinct()) }
        return Extracted(winners.values.map { evidence(it, facts) }.sortedBy { it.id }, merged.sortedWith(compareBy({ it.path }, { it.line })), notes)
    }

    private fun rule(p: Proposal, score: Double?, hash: String, how: String = ""): ConventionRule = ConventionRule(
        id = PREFIX + ReviewCache.digest(p.kind + p.params.toSortedMap()).take(10),
        kind = p.kind,
        description = p.description,
        params = p.params,
        severity = "BLOCK",
        review = score?.let { RuleReview("KEEP", "JEV 원문 일치 ${"%.2f".format(it)}$how") },
        origin = DocOrigin(p.section.path, p.section.line, p.section.heading, p.quote, hash),
    )

    internal fun repair(jev: Jev, root: Path, facts: Facts, sections: List<DocSection>, extracted: Extracted, confirmed: Map<String, Double>, attempted: Collection<String>, ask: (String) -> String): Extracted {
        if (!jev.enabled) return Extracted(extracted.rules, extracted.documents, extracted.notes, attempted.toList())
        val targets = extracted.rules.filter { r ->
            r.review?.verdict == "KEEP" && misconverted(r) && r.origin != null && r.id !in attempted && confirmed[r.id]?.let { it < CONFIRMED } == true
        }.take(MAX_REPAIRS)
        if (targets.isEmpty()) return Extracted(extracted.rules, extracted.documents, extracted.notes, attempted.toList())
        val tree = structure(facts)
        var rules = extracted.rules
        var documents = extracted.documents
        val notes = extracted.notes.toMutableList()
        val tried = attempted.toMutableList()
        for (old in targets) {
            val o = old.origin!!
            val section = sections.firstOrNull { it.path == o.path && quoted(it.text, o.quote) } ?: continue
            tried += old.id
            val where = "${o.path}:${o.line}"
            val proposals = runCatching {
                parse(ask(prompt(listOf(section), tree) + "\n\n" + repairNote(old, old.evidence.examples.mapNotNull { snippet(root, it) })), listOf(section to sectionHash(section)), notes).flatMap { it.proposals }
            }.getOrElse { e -> notes += "$where 기존 위반이 많은 문서 규칙을 다시 옮기지 못했습니다(${e.message}): ${old.description}"; continue }
                .filter { p -> quoted(p.quote, o.quote) || quoted(o.quote, p.quote) }
            val faith = verify(jev, proposals, tree, single = true).orEmpty()
            val fixed = proposals.mapNotNull { p -> faith[p]?.takeIf { it >= FAITHFUL }?.let { score -> evidence(rule(p, score, o.hash, ", 반례로 다시 옮김"), facts) } }
                .firstOrNull { !misconverted(it) }
            if (fixed == null) {
                notes += "$where 기존 위반이 많은 문서 규칙을 반례로 다시 옮겼지만 원문과 맞고 기존 코드가 지키는 규칙을 얻지 못했습니다: ${old.description}"
                continue
            }
            rules = rules.filterNot { it.id == old.id || it.id == fixed.id } + fixed
            documents = documents.map { c -> if (old.id in c.rules) c.copy(rules = c.rules.map { if (it == old.id) fixed.id else it }.distinct()) else c }
            notes += "$where 기존 위반이 많은 문서 규칙을 반례로 다시 옮겼습니다: ${old.description} → ${fixed.description}"
        }
        return Extracted(rules.sortedBy { it.id }, documents, notes, tried.distinct())
    }

    private fun repairNote(rule: ConventionRule, examples: List<String>): String = buildString {
        appendLine("다시 옮기기: 구간 s0 의 아래 규칙을 옮겼더니 기존 코드 ${rule.evidence.total}곳 중 ${rule.evidence.total - rule.evidence.compliant}곳이 어긴다.")
        appendLine("걸린 코드가 원문은 지키는데 규칙을 원문보다 좁거나 엄격하게 옮겨서 걸렸을 수 있다. 아래 반례를 보고 원문과 다시 대조해, 원문 그대로인 규칙으로 고쳐 옮겨라.")
        appendLine("원문이 허용하는 다른 방식(예: 클래스에 붙여도 된다면 inherit)은 params 에 담는다. 원문대로 옮길 수 없으면 rules 를 비우고 그 문장을 remaining 에 넣는다.")
        appendLine("지금 규칙: " + Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), kotlinx.serialization.json.buildJsonObject {
            put("kind", JsonPrimitive(rule.kind))
            put("params", kotlinx.serialization.json.JsonObject(rule.params.mapValues { (_, v) -> JsonArray(v.map(::JsonPrimitive)) }))
            put("description", JsonPrimitive(rule.description))
            put("quote", JsonPrimitive(rule.origin?.quote.orEmpty()))
        }))
        appendLine("반례:")
        examples.forEach { appendLine(it); appendLine() }
    }.trimEnd()

    internal fun evidence(rule: ConventionRule, facts: Facts): ConventionRule {
        val samples = Probes.byKind(rule.kind)?.samples(facts, rule.params).orEmpty()
        return rule.copy(evidence = Evidence(samples.count { it.ok }, samples.size, samples.filter { !it.ok }.take(3).map { "${it.file}:${it.line}" }))
    }

    private fun batches(pending: List<Pair<DocSection, String>>): List<List<Pair<DocSection, String>>> {
        val out = mutableListOf<MutableList<Pair<DocSection, String>>>()
        var used = 0
        for (item in pending) {
            val size = item.first.heading.length + item.first.text.length
            if (out.isEmpty() || used + size > MAX_PROMPT_CHARS) { out += mutableListOf<Pair<DocSection, String>>(); used = 0 }
            out.last() += item
            used += size
        }
        return out
    }

    internal fun structure(facts: Facts): String {
        val dirs = facts.files.groupBy { it.path.substringBeforeLast('/', "") }.entries.sortedWith(compareBy({ it.value.first().test }, { it.key }))
        val lines = dirs.take(MAX_DIRECTORIES).map { (dir, files) ->
            val names = files.flatMap { f -> f.types.filter { it.topLevel }.map { it.name } }.distinct().sorted()
            "- $dir (${files.first().pkg.ifEmpty { "기본 패키지" }}, 파일 ${files.size}개): ${names.take(3).joinToString()}${if (names.size > 3) " 등" else ""}"
        }
        return (lines + listOfNotNull("- 그 밖의 디렉터리 ${dirs.size - MAX_DIRECTORIES}개".takeIf { dirs.size > MAX_DIRECTORIES })).joinToString("\n")
    }

    internal fun prompt(sections: List<DocSection>, tree: String = "", extracted: List<List<String>> = emptyList()): String = buildString {
        appendLine("""
            아래는 한 프로젝트 문서에서 코드를 만들거나 고칠 때 따라야 할 규칙이 적힌 구간들이다. 각 구간에서 정적 분석으로 기계적으로 확인할 수 있는 규칙만 골라 아래 규칙 종류(kind)로 옮겨라.
            - 규칙이 합리적인지는 따지지 말고 적힌 그대로 옮긴다.
            - 원문에 없는 조건을 더하거나 대상을 넓히거나 좁히지 않는다.
            - 원문이 역할 이름(컨트롤러, 리포지토리, 웹 어댑터, 서비스 구현 등)으로 대상을 가리키면 아래 프로젝트 구조에서 그 역할이 있는 디렉터리·패키지·이름으로 옮긴다. 구조에서도 대상을 가릴 수 없으면 옮기지 않는다.
            - 검사는 Java·Kotlin 소스(src/main, src/test)에만 한다. 다른 언어, 설정 파일, 문서, 커밋, 빌드 규칙은 옮기지 않는다.
            - 한 문장에 규칙이 여럿이면 나눠 옮긴다. 옮기지 못한 규칙 문장은 remaining 에 원문 그대로 넣는다.
            - params 는 그 kind 에 적힌 이름만 쓴다(doc-forbidden-import·doc-forbidden-code·doc-file-location 에는 target 이 없다).
            - '새 코드는 ~'처럼 앞으로 만들 코드에 대한 규칙도 옮긴다. 제출 검사는 이번 변경이 새로 만든 위반만 막으므로 기존 코드가 어기고 있어도 된다.
            - quote 는 그 규칙이 적힌 원문 문장을 그대로 복사한다.
            JSON 만 출력하라(설명·코드펜스 금지):
            {"sections":[{"id":"s0","rules":[{"kind":"...","params":{"이름":["값"]},"description":"규칙 한 문장(한국어)","quote":"원문 문장"}],"remaining":["옮기지 못한 규칙 원문 문장"]}]}
            모든 구간 id 에 대해 답하라. 규칙이 없으면 rules 와 remaining 을 빈 배열로 둔다.
        """.trimIndent())
        appendLine()
        appendLine(KINDS)
        appendLine("""예: "컨트롤러는 리포지토리를 쓰지 않는다"(구조에 …/controller 와 …/repository 가 있을 때) → {"kind":"doc-forbidden-import","params":{"scope":["main"],"path":["**/controller/**"],"import":["\\.repository\\."]}}""")
        if (tree.isNotBlank()) {
            appendLine()
            appendLine("프로젝트 구조(디렉터리 (패키지, 파일 수): 대표 타입):")
            appendLine(tree)
        }
        appendLine()
        appendLine("구간:")
        sections.forEachIndexed { i, s ->
            appendLine("[s$i] ${s.path}:${s.line} (${s.heading})")
            appendLine(s.text)
            extracted.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let { done -> appendLine("(이미 정적으로 옮긴 문장이라 다시 옮기지 않는다: ${done.joinToString(" / ")})") }
            appendLine()
        }
    }.trimEnd()

    internal fun parse(response: String, batch: List<Pair<DocSection, String>>, notes: MutableList<String>): List<SectionResult> {
        val start = response.indexOf('{')
        val end = response.lastIndexOf('}')
        require(start in 0 until end) { "JSON 응답이 없습니다" }
        val items = Json.parseToJsonElement(response.substring(start, end + 1)).jsonObject["sections"] as? JsonArray
            ?: throw IllegalArgumentException("sections 가 없습니다")
        val byId = items.filterIsInstance<JsonObject>().associateBy { (it["id"] as? JsonPrimitive)?.contentOrNull }
        return batch.mapIndexedNotNull { i, (section, hash) ->
            val item = byId["s$i"] ?: return@mapIndexedNotNull null
            val proposals = mutableListOf<Proposal>()
            val remaining = (item["remaining"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }.toMutableList()
            for (element in (item["rules"] as? JsonArray).orEmpty()) {
                val proposal = proposal(element, section, notes)
                if (proposal != null) proposals += proposal
                else text(element, "quote")?.takeIf { quoted(section.text, it) }?.let(remaining::add)
            }
            SectionResult(section, hash, proposals, remaining)
        }
    }

    private fun proposal(element: JsonElement, section: DocSection, notes: MutableList<String>): Proposal? {
        val obj = element as? JsonObject ?: return null
        val where = "${section.path}:${section.line}"
        val description = text(obj, "description").orEmpty()
        val quote = text(obj, "quote").orEmpty()
        if (description.isEmpty() || !quoted(section.text, quote)) {
            notes += "$where 원문에서 찾을 수 없는 인용이라 뺐습니다: ${quote.take(60)}"
            return null
        }
        val raw = (obj["params"] as? JsonObject).orEmpty().mapValues { (_, v) -> values(v) }
        val params = try {
            DocProbes.normalize(text(obj, "kind").orEmpty(), raw)
        } catch (e: IllegalArgumentException) {
            notes += "$where 규칙 형식이 맞지 않아 뺐습니다(${e.message}): $description"
            return null
        }
        return Proposal(section, text(obj, "kind")!!, params, description, quote)
    }

    private fun text(element: JsonElement, key: String): String? =
        ((element as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun values(value: JsonElement): List<String> = when (value) {
        is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        is JsonPrimitive -> listOfNotNull(value.contentOrNull)
        else -> emptyList()
    }

    private fun squash(s: String) = s.filter { it.isLetterOrDigit() }.lowercase()

    private fun twin(rule: ConventionRule): String = rule.origin?.let { "${rule.kind}|${it.path}|${squash(it.quote)}" } ?: rule.id

    private fun quoted(text: String, quote: String): Boolean {
        val q = squash(quote)
        return q.isNotEmpty() && q in squash(text)
    }

    internal fun verify(jev: Jev, proposals: List<Proposal>, tree: String = "", single: Boolean = false): Map<Proposal, Double>? {
        if (proposals.isEmpty()) return emptyMap()
        if (!jev.enabled) return null
        val pool = java.util.concurrent.Executors.newFixedThreadPool(Guidelines.PARALLEL)
        try {
            fun ask(batches: List<List<Proposal>>): Map<Proposal, Double>? =
                batches.map { batch -> pool.submit<Map<Proposal, Double>?> { verifyBatch(jev, batch, tree) } }
                    .map { it.get() ?: return null }.fold(emptyMap()) { a, b -> a + b }
            if (single) return ask(proposals.map { listOf(it) })
            val first = ask(verifyBatches(proposals, Guidelines.weight(tree))) ?: return null
            val unsure = first.filterValues { it >= RECHECK_FROM && it < RECHECK_UNTIL }.keys
            if (unsure.isEmpty()) return first
            val again = ask(unsure.map { listOf(it) }) ?: return first
            return first + again.mapValues { (p, s) -> (first.getValue(p) + s) / 2 }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun verifyBatch(jev: Jev, batch: List<Proposal>, tree: String): Map<Proposal, Double>? {
        val sections = batch.map { it.section }.distinct()
        val state = buildMap<String, Any> {
            put("policy", VERSION)
            put("kinds", KINDS)
            if (tree.isNotBlank()) put("structure", tree)
            put("sections", sections.mapIndexed { j, s -> mapOf("id" to "s$j", "source" to "${s.path}:${s.line}", "heading" to s.heading, "text" to s.text) })
            put("candidates", batch.mapIndexed { i, p -> mapOf("id" to "c$i", "section" to "s${sections.indexOf(p.section)}", "kind" to p.kind, "params" to p.params, "description" to p.description, "quote" to p.quote) })
        }
        val questions = batch.indices.associate { i ->
            "f$i" to NoulQuestion.of("f$i", "후보 규칙 c$i 가 그 section 의 text 에 적힌 규칙을 그대로 옮긴 것인가? kinds 의 뜻대로 kind 와 params 를 읽었을 때 검사 대상(scope, path, target, when, name)과 조건(import, annotation, pattern, location, max 등)이 원문 규칙과 같으면 Yes. 원문에 없는 조건을 더했거나, 대상을 넓히거나 좁혔거나, 원문과 다른 규칙이면 No. 원문이 애너테이션 값의 언어·형식까지 정했는데(예: 한국어 설명) value 없이 애너테이션만 요구하면 원문보다 느슨하므로 No. 원문이 클래스나 메서드 중 한 곳에 붙이면 된다고 했는데 inherit 없이 메서드마다 요구하면 원문보다 좁으므로 No. 규칙이 합리적인지는 따지지 않는다. scope 와 path 가 원문이 말한 대상과 같은지 따로 확인한다: 원문이 '테스트에서'·'파서는'처럼 대상을 한정했는데 all 이거나 path 없이 전체를 검사하면 넓힌 것이고, 원문에 한정이 없는데 main 이나 test 로 줄였으면 좁힌 것이다. 한 문장에 규칙이 여럿이면 그중 하나만 옮겨도 옮긴 부분이 원문과 같으면 Yes. 원문이 역할 이름(서비스 클래스, JPA 엔티티, 컨트롤러, 리포지토리 등)으로 가리킨 대상을 그 역할의 애너테이션(when)이나 structure 에 있는 경로·패키지·이름으로 가린 것은 대상을 바꾼 것이 아니다. 검사는 이번 변경이 새로 만든 위반만 막으므로 '새 코드는 ~' 규칙을 모든 파일 대상으로 옮긴 것도 같은 규칙이다.")
                .withCriteria("원문 규칙을 그대로 옮김", "원문과 다르거나 원문에 없는 규칙")
        }
        val answers = jev.probabilities(state, questions) ?: return null
        return batch.withIndex().mapNotNull { (i, p) -> answers["f$i"]?.let { p to it } }.toMap()
    }

    private fun verifyBatches(proposals: List<Proposal>, reserved: Int): List<List<Proposal>> {
        val budget = Guidelines.MAX_STATE_WEIGHT - Guidelines.weight(KINDS) - reserved
        val out = mutableListOf<MutableList<Proposal>>()
        var used = 0
        for (p in proposals) {
            fun cost(batch: List<Proposal>?) = Guidelines.weight(p.description + p.quote + p.params) +
                if (batch?.any { it.section == p.section } == true) 0 else Guidelines.weight(p.section.heading + p.section.text)
            val last = out.lastOrNull()
            if (last == null || last.size >= Guidelines.BATCH || used + cost(last) > budget) { out += mutableListOf<Proposal>(); used = 0 }
            used += cost(out.last())
            out.last() += p
        }
        return out
    }

    fun asConvention(model: DocRuleModel): ConventionModel = ConventionModel(null, 0, 0, emptyMap(), model.rules)

    fun carryOver(previous: DocRuleModel?, extracted: Extracted, confirmed: Map<String, Double> = emptyMap(), others: List<ConventionRule> = emptyList()): DocRuleModel {
        val decided = previous?.rules.orEmpty().filter { it.decidedBy == "user" }
        val rules = extracted.rules.map { r ->
            decided.firstOrNull { it.id == r.id && it.kind == r.kind && it.params == r.params }?.let { r.copy(approved = it.approved, rejected = it.rejected, decidedBy = it.decidedBy) } ?: autoDecide(r, confirmed[r.id])
        }
        val (resolved, conflicts) = resolve(rules, others)
        return DocRuleModel(resolved, extracted.documents, extracted.notes, attempted = extracted.attempted, conflicts = conflicts)
    }

    internal fun resolve(rules: List<ConventionRule>, others: List<ConventionRule> = emptyList()): Pair<List<ConventionRule>, List<RuleConflict>> {
        val found = conflicts(rules, others)
        if (found.isEmpty()) return rules to emptyList()
        val byId = (others + rules).associateBy { it.id }
        val dropped = linkedMapOf<String, String>()
        val out = found.map { (required, forbidden) ->
            val a = byId.getValue(required)
            val b = byId.getValue(forbidden)
            val loser = when {
                a.decidedBy == "user" && b.decidedBy == "user" -> null
                a.decidedBy == "user" -> b.takeIf { isDocRule(it.id) }
                else -> a
            }
            val reason = "'${a.description}'(필요)와 '${b.description}'(금지)는 함께 지킬 수 없다. " + when {
                loser == a -> "금지 규칙을 남기고 필요 규칙은 따르지 않는다"
                loser != null -> "사용자가 승인한 필요 규칙을 남기고 금지 규칙은 따르지 않는다"
                b.decidedBy == "user" -> "둘 다 사용자가 정한 규칙이라 그대로 둔다"
                else -> "사용자가 승인한 필요 규칙과 컨벤션 규칙이 부딪친다. 컨벤션 규칙은 여기서 끄지 않으므로 사용자가 하나를 거절해야 한다"
            }
            loser?.let { dropped.putIfAbsent(it.id, reason) }
            RuleConflict(if (loser == a) b.id else a.id, loser?.id.orEmpty(), reason)
        }.distinct()
        return rules.map { r -> if (r.id in dropped) r.copy(approved = false, rejected = true, decidedBy = "ai-review") else r } to out
    }

    private val requiring = setOf("doc-required-code", "doc-required-annotation")

    internal fun conflicts(rules: List<ConventionRule>, others: List<ConventionRule> = emptyList()): List<Pair<String, String>> {
        val active = (rules + others).filter { it.approved && !it.rejected }
        return rules.filter { it.approved && !it.rejected && it.kind in requiring }.flatMap { a ->
            synthetic(a).flatMap { probe ->
                active.filter { it.id != a.id }.mapNotNull { b ->
                    val check = Probes.byKind(b.kind) ?: return@mapNotNull null
                    val hit = runCatching { check.samples(Facts(listOf(probe.first)), b.params) }.getOrDefault(emptyList()).any { !it.ok && it.line in probe.second }
                    (a.id to b.id).takeIf { hit }
                }
            }
        }.distinct()
    }

    private fun synthetic(rule: ConventionRule): List<Pair<ConvFile, Set<Int>>> {
        val p = rule.params
        val scope = p["scope"]?.firstOrNull() ?: "all"
        val target = p["target"]?.firstOrNull() ?: "file"
        val kept = mutableListOf<String>()
        when (rule.kind) {
            "doc-required-code" -> p.getValue("pattern").forEach { pattern ->
                val example = RegexExample.of(pattern) ?: return emptyList()
                kept += if (p["text"]?.firstOrNull() == "string") "val ilsimProbe = \"$example\"" else example
            }
            "doc-required-annotation" -> {
                val values = p["value"].orEmpty().map(::Regex)
                val value = if (values.isEmpty()) null else listOf("설명", "probe", "Probe 설명", "1").firstOrNull { v -> values.all { it.containsMatchIn(v) } } ?: return emptyList()
                kept += "@" + p.getValue("annotation").first() + value?.let { "(\"$it\")" }.orEmpty()
            }
        }
        val marks = p["when"].orEmpty().map { "@$it" }
        val method = p["name"]?.firstNotNullOfOrNull { RegexExample.of(it)?.takeIf { n -> Regex("""[A-Za-z_]\w*""").matches(n) } } ?: "probeCase"
        return listOf(false, true).filter { test -> scope == "all" || (scope == "test") == test }.mapNotNull { test ->
            val type = if (test) "IlsimProbeTest" else "IlsimProbe"
            val path = probePath(p["path"], test, type) ?: return@mapNotNull null
            val annotationRule = rule.kind == "doc-required-annotation"
            val lines = mutableListOf("package ilsimprobe", "")
            val inserted = mutableSetOf<Int>()
            fun add(line: String, mark: Boolean = false) { lines += line; if (mark) inserted += lines.size }
            if (target in setOf("class", "test-class")) { marks.forEach { add(it) }; kept.forEach { add(it, true) } }
            add("class $type {")
            if (target in setOf("method", "test-method")) {
                if (target == "test-method") add("    @Test")
                marks.forEach { add("    $it") }
                if (annotationRule) kept.forEach { add("    $it", true) }
                add("    fun $method() {")
                if (!annotationRule) kept.forEach { add("        $it", true) }
                add("    }")
            } else if (target == "file" && !annotationRule) kept.forEach { add("    $it", true) }
            add("}")
            val file = FactScanner.parse(path, "kt", test, lines) ?: return@mapNotNull null
            val own = Probes.byKind(rule.kind) ?: return@mapNotNull null
            val ok = runCatching { own.samples(Facts(listOf(file)), p) }.getOrDefault(emptyList()).let { s -> s.isNotEmpty() && s.all { it.ok } }
            (file to inserted.toSet()).takeIf { ok && inserted.isNotEmpty() }
        }
    }

    private fun probePath(globs: List<String>?, test: Boolean, type: String): String? {
        val plain = "src/${if (test) "test" else "main"}/kotlin/ilsimprobe/$type.kt"
        if (globs.isNullOrEmpty()) return plain
        val glob = globs.first()
        val made = glob.replace("**/", "").replace("**", "ilsimprobe").replace("*", type).let { if (it.endsWith(".kt") || it.endsWith(".java")) it else it.trimEnd('/') + "/$type.kt" }
        val candidate = if (made.startsWith("src/")) made else "src/${if (test) "test" else "main"}/kotlin/$made"
        return listOf(candidate, made).firstOrNull { DocProbes.glob(glob).matches(Path.of(it)) }
    }

    internal fun autoDecide(r: ConventionRule, confirmed: Double? = null): ConventionRule {
        val checked = confirmed?.let { p -> r.copy(review = r.review?.copy(reason = r.review.reason + ", 기존 위반이 원문을 어길 확률 ${"%.2f".format(p)}")) } ?: r
        return when {
            r.review?.verdict != "KEEP" -> r.copy(approved = false, rejected = false, decidedBy = null)
            !misconverted(r) -> r.copy(approved = true, rejected = false, decidedBy = "ai-review")
            confirmed == null -> r.copy(approved = false, rejected = false, decidedBy = null)
            confirmed >= CONFIRMED -> checked.copy(approved = true, rejected = false, decidedBy = "ai-review")
            confirmed <= MISCONVERTED -> checked.copy(approved = false, rejected = true, decidedBy = "ai-review")
            else -> checked.copy(approved = false, rejected = false, decidedBy = null)
        }
    }

    internal fun misconverted(r: ConventionRule) = (r.evidence.total - r.evidence.compliant) * MISCONVERTED_DIVISOR > r.evidence.total

    fun frozen(model: DocRuleModel): Map<String, Int> =
        model.rules.filter { it.approved && !it.rejected }.associate { it.id to it.evidence.total - it.evidence.compliant }.filterValues { it > 0 }.toSortedMap()

    fun recheck(jev: Jev, root: Path, rules: List<ConventionRule>): Map<String, Double> {
        if (!jev.enabled) return emptyMap()
        return rules.filter { it.review?.verdict == "KEEP" && misconverted(it) && it.origin != null }.mapNotNull { r ->
            val examples = r.evidence.examples.mapNotNull { snippet(root, it) }
            if (examples.isEmpty()) return@mapNotNull null
            val o = r.origin!!
            val state = mapOf(
                "quote" to o.quote,
                "source" to "${o.path}:${o.line} ${o.heading}",
                "rule" to r.description,
                "violations" to "기존 코드 ${r.evidence.total}곳 중 ${r.evidence.total - r.evidence.compliant}곳이 rule 에 걸림",
                "examples" to examples,
            )
            runCatching { jev.probability(state, RECHECK) }.getOrNull()?.let { r.id to it }
        }.toMap()
    }

    internal fun snippet(root: Path, location: String): String? {
        val path = location.substringBeforeLast(':')
        val line = location.substringAfterLast(':').toIntOrNull() ?: return null
        val lines = runCatching { root.resolve(path).readLines() }.getOrNull() ?: return null
        if (line < 1 || line > lines.size) return null
        val keep = sortedSetOf<Int>()
        val decl = lines.indexOfFirst { topType.containsMatchIn(it) }
        if (decl in 0 until line - 1) {
            var start = decl
            while (start > 0 && decl - start < MAX_HEADER && lines[start - 1].trimStart().startsWith("@")) start--
            (start..decl).forEach { keep += it }
        }
        ((line - 4).coerceAtLeast(0) until (line + 1).coerceAtMost(lines.size)).forEach { keep += it }
        val text = buildString {
            var previous = -1
            for (i in keep) {
                if (previous >= 0 && i > previous + 1) appendLine("    …")
                appendLine(lines[i])
                previous = i
            }
        }.trimEnd()
        return "$location\n" + text.take(MAX_SNIPPET)
    }

    private val topType = Regex("""^(?:(?:public|internal|private|abstract|open|final|sealed|data|enum|annotation)\s+)*(?:class|interface|object|record|enum)\s+\w""")

    private val RECHECK = NoulQuestion.of("m", "examples 는 기존 코드에서 rule(문서에서 옮긴 정적 규칙)에 걸린 곳이다. 이 코드가 quote(프로젝트 문서 원문)가 정한 규칙을 실제로 어기는가? 원문이 허용하는 다른 방식(다른 위치·범위에 표시, 원문이 적은 예외)을 따르고 있어서 rule 이 원문보다 좁거나 엄격하게 옮겨져 걸린 것이면 No. 원문이 정한 것을 코드가 따르지 않으면 Yes. 원문이 합리적인지는 따지지 않는다.")
        .withCriteria("기존 코드가 문서 원문을 어김", "기존 코드는 원문을 지키고 옮긴 규칙만 어김")

    const val CONFIRMED = 0.6
    const val MISCONVERTED = 0.4
    private const val MAX_SNIPPET = 900
    private const val MAX_HEADER = 5

    fun decide(model: DocRuleModel, ids: Collection<String>, approve: Boolean): DocRuleModel {
        val unknown = ids.filterNot { id -> model.rules.any { it.id == id } }
        require(unknown.isEmpty()) { "알 수 없는 문서 규칙 id: ${unknown.joinToString()}" }
        return model.copy(rules = model.rules.map { if (it.id in ids) it.copy(approved = approve, rejected = !approve, decidedBy = "user") else it })
    }

    fun undecidedIds(model: DocRuleModel): List<String> = model.rules.filter { !it.approved && !it.rejected }.map { it.id }

    fun introduced(worktree: Path, model: DocRuleModel, violations: List<Violation>, base: (String) -> List<String>?): List<Violation> {
        val (doc, other) = violations.partition { isDocRule(it.ruleId) }
        if (doc.isEmpty()) return violations
        val paths = doc.map { it.file }.distinct()
        val before = paths.mapNotNull { path -> base(path)?.let { FactScanner.parse(path, path.substringAfterLast('.'), "src/test/" in path, it) } }
        val old = ConventionChecker.check(Facts(before), asConvention(model), approvedOnly = true)
        return other + Introduced.filter(doc, old, { runCatching { worktree.resolve(it).readLines() }.getOrNull() }) { path -> before.firstOrNull { it.path == path }?.lines }
    }

    data class LedgerEntry(val path: String, val line: Int, val heading: String, val text: String, val status: String, val reason: String? = null)

    const val STATIC = "정적 규칙"
    const val STATIC_UNVERIFIED = "정적 규칙이 못 잡아 JEV 판정"
    const val STATIC_REJECTED = "정적 규칙 거절됨(JEV 판정)"
    const val TO_JEV = "JEV 판정으로 넘김"
    const val MISSING = "누락(판정 대상이 아닌 구간의 규칙 문장)"
    const val NOT_RULE = "규칙 구간 아님(문장은 판정 못 함)"
    const val NOT_CLAUSE = "규칙 문장 아님(JEV 판정)"
    const val RULE_SENTENCE = 0.5

    fun ledger(sections: List<DocSection>, model: DocRuleModel, scores: Map<DocSection, Double>? = null, unverified: Map<String, String> = emptyMap()): List<LedgerEntry> {
        val coverage = model.documents.associateBy { it.hash }
        val places = model.documents.groupBy { it.path to it.heading }
        return sections.flatMap { s ->
            val covered = coverage[sectionHash(s)] ?: places[s.path to s.heading]?.minByOrNull { kotlin.math.abs(it.line - s.line) }
            val judged = scores?.get(s)?.let { it > Guidelines.IRRELEVANT } ?: (covered != null)
            StaticDocRules.clauses(s).map { clause ->
                val matched = model.rules.filter { r -> r.origin?.let { o -> o.path == s.path && (quoted(clause, o.quote) || quoted(o.quote, clause)) } == true }
                val live = matched.filter { !it.rejected }
                val (status, reason) = when {
                    live.any { it.id !in unverified } -> STATIC to null
                    live.isNotEmpty() -> STATIC_UNVERIFIED to live.joinToString("; ") { "${it.id} ${unverified[it.id].orEmpty()}" }
                    matched.isNotEmpty() -> STATIC_REJECTED to null
                    judged -> TO_JEV to null
                    else -> NOT_RULE to null
                }
                LedgerEntry(s.path, s.line, s.heading, clause, status, reason)
            }
        }
    }

    fun screen(jev: Jev, entries: List<LedgerEntry>): List<LedgerEntry>? {
        val targets = entries.filter { it.status == NOT_RULE }.distinct()
        if (targets.isEmpty()) return entries
        if (!jev.enabled) return null
        val pool = java.util.concurrent.Executors.newFixedThreadPool(Guidelines.PARALLEL)
        val scores = try {
            targets.chunked(Guidelines.BATCH).map { batch ->
                batch to pool.submit<Map<String, Double>?> {
                    val state = mapOf("clauses" to batch.mapIndexed { i, e -> mapOf("id" to "c$i", "source" to "${e.path}:${e.line}", "heading" to e.heading, "text" to e.text) })
                    jev.probabilities(state, batch.indices.associate { "c$it" to clauseQuestion(it) })
                }
            }.flatMap { (batch, answer) ->
                val answers = answer.get() ?: return null
                batch.mapIndexed { i, e -> e to (answers["c$i"] ?: 1.0) }
            }.toMap()
        } finally {
            pool.shutdownNow()
        }
        return entries.map { e -> if (e.status != NOT_RULE) e else e.copy(status = if ((scores[e] ?: 1.0) < RULE_SENTENCE) NOT_CLAUSE else MISSING) }
    }

    private fun clauseQuestion(i: Int): NoulQuestion =
        NoulQuestion.of("c$i", "문장 c$i 는 이 프로젝트에서 코드나 테스트를 만들거나 고칠 때 지켜야 할 구체적인 규칙(정해진 동작·구조·이름·형식·금지 사항·테스트 방식 등)인가? 문서 소개, 배경, 이유 설명, 사실이나 현재 상태 서술, 예시, 일정, 설치·실행 안내면 No. 규칙이 합리적인지는 따지지 않는다.")
            .withCriteria("지켜야 할 코드·테스트 규칙", "설명·서술·안내 문장")

    fun ledgerReport(entries: List<LedgerEntry>): String = buildString {
        if (entries.isEmpty()) {
            appendLine("문서 규칙 문장 처리 내역: 규칙 문형 문장이 없습니다")
            return@buildString
        }
        val counts = entries.groupingBy { it.status }.eachCount()
        appendLine("문서 규칙 문장 처리 내역: ${entries.size}개 중 " + listOf(STATIC, STATIC_UNVERIFIED, STATIC_REJECTED, TO_JEV, MISSING, NOT_RULE, NOT_CLAUSE).filter { (counts[it] ?: 0) > 0 }.joinToString(", ") { "$it ${counts[it]}" })
        val notes = mapOf(
            MISSING to "JEV가 규칙 문장이라고 봤지만 그 구간이 규칙 구간 점수가 낮아 제출 때 판정하지 않음",
            NOT_RULE to "규칙 구간 점수가 낮고 JEV를 못 써 문장이 규칙인지 확인 못 함",
            STATIC_UNVERIFIED to "정적 규칙이 자체 시험에서 위반을 잡지 못해 제출 때 JEV 판정에 맡김",
        )
        notes.forEach { (status, note) ->
            val shown = entries.filter { it.status == status }
            if (shown.isEmpty()) return@forEach
            appendLine("  $status ($note):")
            shown.take(MAX_REMAINING).forEach { appendLine("    ${it.path}:${it.line} ${it.text}${it.reason?.let { r -> "  [$r]" }.orEmpty()}") }
            if (shown.size > MAX_REMAINING) appendLine("    …외 ${shown.size - MAX_REMAINING}개")
        }
    }

    fun report(model: DocRuleModel): String = buildString {
        val rules = model.rules
        val remaining = model.documents.flatMap { d -> d.remaining.map { "${d.path}:${d.line} $it" } }
        if (rules.isEmpty() && remaining.isEmpty()) return@buildString
        appendLine()
        appendLine("문서 규칙 ${rules.size}개 (문서에서 옮긴 정적 규칙. 승인하면 바꾼 코드에 새로 생기는 위반을 막는다):")
        val dropped = model.conflicts.associateBy { it.dropped }
        for (r in rules) {
            val auto = r.decidedBy == "ai-review"
            val existing = r.evidence.total - r.evidence.compliant
            val state = when {
                r.approved -> (if (auto) "자동 승인" else "승인됨") + if (existing > 0) "·기존 위반 ${existing}건 동결" else ""
                r.rejected && r.id in dropped -> "자동 끔: 규칙 충돌, ${dropped.getValue(r.id).kept}를 남김"
                r.rejected -> if (auto) "자동 거절: 기존 코드는 문서 원문을 지키는데 옮긴 규칙만 어겨 잘못 옮긴 것으로 판정" else "거절됨"
                r.review?.verdict == "KEEP" && misconverted(r) -> "제안: 기존 코드 다수가 어겨 잘못 옮겼는지 확인 필요"
                else -> "제안"
            }
            appendLine("  [$state·${r.severity}] ${r.description}  <${r.id}>")
            val faith = r.review?.reason ?: "JEV 확인 안 됨"
            val origin = r.origin?.let { "${it.path}:${it.line}" }.orEmpty()
            appendLine("      $origin · $faith · 현재 코드 위반 ${r.evidence.total - r.evidence.compliant}/${r.evidence.total}")
        }
        if (model.conflicts.isNotEmpty()) {
            appendLine("규칙 충돌 ${model.conflicts.size}건 (함께 지킬 수 없는 규칙, 금지 규칙을 남기고 사용자가 정한 규칙은 그대로 둔다):")
            model.conflicts.forEach { appendLine("  ${it.kept} 남김${if (it.dropped.isNotEmpty()) ", ${it.dropped} 끔" else ""}: ${it.reason}") }
        }
        if (remaining.isNotEmpty()) {
            appendLine("정적 규칙으로 옮기지 못한 문장 ${remaining.size}개 (제출 때 JEV 문서 지침 판정으로 확인):")
            remaining.take(MAX_REMAINING).forEach { appendLine("  $it") }
            if (remaining.size > MAX_REMAINING) appendLine("  …외 ${remaining.size - MAX_REMAINING}개")
        }
    }

    internal val KINDS = """
        params 공통(값은 모두 문자열 배열):
        - scope: ["main"] 프로덕트 코드, ["test"] 테스트 코드, ["all"] 둘 다. 필수.
        - path: 검사할 파일의 저장소 기준 경로 glob(예: "**/domain/**"). 생략하면 scope 전체.
        - target: 검사 대상. file(파일), class(최상위 타입), test-class(테스트 파일의 최상위 타입), method(메서드·함수), test-method(@Test·@ParameterizedTest 등이 붙은 메서드).
        - when: 이 애너테이션 중 하나가 붙은 대상만 검사(@ 없이, 예: ["Nested"]). target 이 필요하다.
        - name: 이름 전체가 이 정규식과 맞는 대상만 검사. target 이 필요하다.
        - text: ["code"] 주석과 문자열 내용을 뺀 코드(기본), ["string"] 주석만 뺀 코드(SQL·JSON 경로처럼 문자열 안 내용에 대한 규칙일 때), ["raw"] 원래 줄(주석에 대한 규칙일 때만, pattern 에 // 같은 주석 표시를 넣는다).
        kind:
        - doc-forbidden-import: import 경로 일부가 import: [정규식] 중 하나와 맞으면 위반. scope, path.
        - doc-required-annotation: annotation: [이름] 중 하나가 대상에 붙어 있어야 한다. value: [정규식] 이 있으면 그 애너테이션 괄호 안 값이 정규식과 맞아야 한다(예: 한국어 설명은 ["[가-힣]"]). inherit: ["class"] 는 method·test-method 대상에서 원문이 클래스에 붙여도 된다고 할 때만 쓴다(메서드나 그 클래스에 붙어 있으면 통과). target(class|test-class|method|test-method) 필수, when, name, value, inherit.
        - doc-forbidden-annotation: annotation: [이름] 이 붙어 있으면 위반. target(생략하면 애너테이션이 붙은 모든 선언), when, name.
        - doc-name-pattern: pattern: [정규식] 중 하나가 대상 이름 전체와 맞아야 한다(파일은 확장자를 뺀 이름). target(file|class|test-class|method|test-method) 필수, when, name.
        - doc-forbidden-code: pattern: [정규식] 이 한 줄 안에서 찾아지면 위반. scope, path, text.
        - doc-required-code: pattern: [정규식] 이 대상 본문에 적은 순서대로 모두 있어야 한다. target(file|method|test-method) 필수, when, name, text.
        - doc-file-location: 이름(확장자 제외) 전체가 name: [정규식] 과 맞는 파일은 location: [glob] 중 하나에 있어야 한다. scope.
        - doc-max-size: measure: ["lines"](선언부터 닫는 괄호까지 줄 수) 또는 ["nesting"](메서드 본문 안 블록 중첩 깊이, 본문 바로 아래 if 하나면 1), max: ["숫자"] 이하. target(file|method|test-method, nesting 은 메서드만) 필수, when, name.
        - doc-max-occurrences: pattern: [정규식] 이 대상 안에 나오는 횟수가 max: ["숫자"] 이하. target(file|method|test-method) 필수, when, name, text.
        예: "모든 테스트 메서드에 @DisplayName 을 붙인다" → {"kind":"doc-required-annotation","params":{"scope":["test"],"target":["test-method"],"annotation":["DisplayName"]}}
        예: "도메인 패키지는 스프링을 import 하지 않는다" → {"kind":"doc-forbidden-import","params":{"scope":["main"],"path":["**/domain/**"],"import":["^org\\.springframework\\."]}}
    """.trimIndent()
}
