package com.dochiri.ilsim.runner.convention

import com.dochiri.ilsim.runner.ReviewCache
import com.dochiri.ilsim.runner.architecture.Evidence
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
    const val FAITHFUL = 0.3
    const val MAX_PROMPT_CHARS = 24_000
    private const val VERSION = "doc-rules-v1"
    private const val MAX_REMAINING = 20

    class Extracted(val rules: List<ConventionRule>, val documents: List<DocCoverage>, val notes: List<String>)

    @Serializable
    data class DocRuleModel(val rules: List<ConventionRule> = emptyList(), val documents: List<DocCoverage> = emptyList(), val notes: List<String> = emptyList())

    internal class Proposal(val section: DocSection, val kind: String, val params: Map<String, List<String>>, val description: String, val quote: String)

    internal class SectionResult(val section: DocSection, val hash: String, val proposals: List<Proposal>, val remaining: List<String>)

    fun isDocRule(id: String) = id.startsWith(PREFIX)

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
        for (s in sections.filter { (scores[it] ?: 0.0) >= Guidelines.CLEAR }) {
            val hash = ReviewCache.digest("$VERSION|${s.heading}|${s.text}")
            val cached = known.firstOrNull { it.hash == hash }
            if (cached == null) { pending += s to hash; continue }
            coverage += cached.copy(path = s.path, line = s.line, heading = s.heading)
            kept.filter { it.id in cached.rules }.forEach { r -> rules.putIfAbsent(r.id, r.copy(origin = r.origin?.copy(path = s.path, line = s.line, heading = s.heading))) }
        }
        val results = batches(pending).flatMap { batch ->
            try {
                parse(ask(prompt(batch.map { it.first })), batch, notes)
            } catch (e: Exception) {
                notes += "문서 규칙 추출 실패(구간 ${batch.size}개, 다음 분석 때 다시 시도): ${e.message}"
                emptyList()
            }
        }
        val faith = verify(jev, results.flatMap { it.proposals })
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
                val rule = ConventionRule(
                    id = PREFIX + ReviewCache.digest(p.kind + p.params.toSortedMap()).take(10),
                    kind = p.kind,
                    description = p.description,
                    params = p.params,
                    severity = "BLOCK",
                    review = score?.let { RuleReview("KEEP", "JEV 원문 일치 ${"%.2f".format(it)}") },
                    origin = DocOrigin(p.section.path, p.section.line, p.section.heading, p.quote, result.hash),
                )
                rules.putIfAbsent(rule.id, rule)
                rule.id
            }.distinct()
            coverage += DocCoverage(result.section.path, result.section.line, result.section.heading, result.hash, ids, remaining.distinct())
        }
        return Extracted(rules.values.map { evidence(it, facts) }.sortedBy { it.id }, coverage.sortedWith(compareBy({ it.path }, { it.line })), notes)
    }

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

    internal fun prompt(sections: List<DocSection>): String = buildString {
        appendLine("""
            아래는 한 프로젝트 문서에서 코드를 만들거나 고칠 때 따라야 할 규칙이 적힌 구간들이다. 각 구간에서 정적 분석으로 기계적으로 확인할 수 있는 규칙만 골라 아래 규칙 종류(kind)로 옮겨라.
            - 규칙이 합리적인지는 따지지 말고 적힌 그대로 옮긴다.
            - 원문에 없는 조건을 더하거나 대상을 넓히거나 좁히지 않는다. 원문이 대상을 경로·이름·애너테이션으로 가릴 방법을 정하지 않았으면 옮기지 않는다.
            - 검사는 Java·Kotlin 소스(src/main, src/test)에만 한다. 다른 언어, 설정 파일, 문서, 커밋, 빌드 규칙은 옮기지 않는다.
            - 한 문장에 규칙이 여럿이면 나눠 옮긴다. 옮기지 못한 규칙 문장은 remaining 에 원문 그대로 넣는다.
            - quote 는 그 규칙이 적힌 원문 문장을 그대로 복사한다.
            JSON 만 출력하라(설명·코드펜스 금지):
            {"sections":[{"id":"s0","rules":[{"kind":"...","params":{"이름":["값"]},"description":"규칙 한 문장(한국어)","quote":"원문 문장"}],"remaining":["옮기지 못한 규칙 원문 문장"]}]}
            모든 구간 id 에 대해 답하라. 규칙이 없으면 rules 와 remaining 을 빈 배열로 둔다.
        """.trimIndent())
        appendLine()
        appendLine(KINDS)
        appendLine()
        appendLine("구간:")
        sections.forEachIndexed { i, s ->
            appendLine("[s$i] ${s.path}:${s.line} (${s.heading})")
            appendLine(s.text)
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

    private fun quoted(text: String, quote: String): Boolean {
        fun squash(s: String) = s.filter { it.isLetterOrDigit() }.lowercase()
        val q = squash(quote)
        return q.isNotEmpty() && q in squash(text)
    }

    internal fun verify(jev: Jev, proposals: List<Proposal>): Map<Proposal, Double>? {
        if (proposals.isEmpty()) return emptyMap()
        if (!jev.enabled) return null
        val out = mutableMapOf<Proposal, Double>()
        for (batch in verifyBatches(proposals)) {
            val sections = batch.map { it.section }.distinct()
            val state = mapOf(
                "policy" to VERSION,
                "kinds" to KINDS,
                "sections" to sections.mapIndexed { j, s -> mapOf("id" to "s$j", "source" to "${s.path}:${s.line}", "heading" to s.heading, "text" to s.text) },
                "candidates" to batch.mapIndexed { i, p -> mapOf("id" to "c$i", "section" to "s${sections.indexOf(p.section)}", "kind" to p.kind, "params" to p.params, "description" to p.description, "quote" to p.quote) },
            )
            val questions = batch.indices.associate { i ->
                "f$i" to NoulQuestion.of("f$i", "후보 규칙 c$i 가 그 section 의 text 에 적힌 규칙을 그대로 옮긴 것인가? kinds 의 뜻대로 kind 와 params 를 읽었을 때 검사 대상(scope, path, target, when, name)과 조건(import, annotation, pattern, location, max 등)이 원문 규칙과 같으면 Yes. 원문에 없는 조건을 더했거나, 대상을 넓히거나 좁혔거나, 원문과 다른 규칙이면 No. 규칙이 합리적인지는 따지지 않는다.")
                    .withCriteria("원문 규칙을 그대로 옮김", "원문과 다르거나 원문에 없는 규칙")
            }
            val answers = jev.probabilities(state, questions) ?: return null
            batch.forEachIndexed { i, p -> answers["f$i"]?.let { out[p] = it } }
        }
        return out
    }

    private fun verifyBatches(proposals: List<Proposal>): List<List<Proposal>> {
        val budget = Guidelines.MAX_STATE_WEIGHT - Guidelines.weight(KINDS)
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

    fun carryOver(previous: DocRuleModel?, extracted: Extracted): DocRuleModel {
        val decided = previous?.rules.orEmpty().filter { it.decidedBy != null }
        val rules = extracted.rules.map { r ->
            decided.firstOrNull { it.id == r.id && it.kind == r.kind && it.params == r.params }?.let { r.copy(approved = it.approved, rejected = it.rejected, decidedBy = it.decidedBy) } ?: r
        }
        return DocRuleModel(rules, extracted.documents, extracted.notes)
    }

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
        val after = paths.associateWith { runCatching { worktree.resolve(it).readLines() }.getOrNull() }
        val old = ConventionChecker.check(Facts(before), asConvention(model), approvedOnly = true)
        fun key(v: Violation, lines: List<String>?) = Triple(v.ruleId, v.file, lines?.getOrNull(v.line - 1)?.trim())
        val left = old.groupingBy { v -> key(v, before.firstOrNull { it.path == v.file }?.lines) }.eachCount().toMutableMap()
        return other + doc.filter { v ->
            val k = key(v, after[v.file])
            val n = left[k] ?: 0
            if (n > 0) left[k] = n - 1
            n == 0
        }
    }

    fun report(model: DocRuleModel): String = buildString {
        val rules = model.rules
        val remaining = model.documents.flatMap { d -> d.remaining.map { "${d.path}:${d.line} $it" } }
        if (rules.isEmpty() && remaining.isEmpty()) return@buildString
        appendLine()
        appendLine("문서 규칙 ${rules.size}개 (문서에서 옮긴 정적 규칙. 승인하면 바꾼 코드에 새로 생기는 위반을 막는다):")
        for (r in rules) {
            val state = when { r.approved -> "승인됨"; r.rejected -> "거절됨"; else -> "제안" }
            appendLine("  [$state·${r.severity}] ${r.description}  <${r.id}>")
            val faith = r.review?.reason ?: "JEV 확인 안 됨"
            val origin = r.origin?.let { "${it.path}:${it.line}" }.orEmpty()
            appendLine("      $origin · $faith · 현재 코드 위반 ${r.evidence.total - r.evidence.compliant}/${r.evidence.total}")
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
        - text: ["code"] 주석과 문자열 내용을 뺀 코드(기본), ["raw"] 원래 줄(주석이나 문자열에 대한 규칙일 때).
        kind:
        - doc-forbidden-import: import 경로 일부가 import: [정규식] 중 하나와 맞으면 위반. scope, path.
        - doc-required-annotation: annotation: [이름] 중 하나가 대상에 붙어 있어야 한다. target(class|test-class|method|test-method) 필수, when, name.
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
