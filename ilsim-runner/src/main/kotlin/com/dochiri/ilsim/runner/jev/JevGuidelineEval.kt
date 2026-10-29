package com.dochiri.ilsim.runner.jev

import com.dochiri.ilsim.runner.convention.DocProbes
import com.dochiri.ilsim.runner.convention.DocRules
import com.dochiri.ilsim.runner.document.DocSection
import com.dochiri.ilsim.runner.document.ProjectDocs
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class EvalDocument(val file: String, val module: String? = null, val feature: String? = null)

@Serializable
data class EvalSection(val rule: Boolean? = null, val module: String? = null, val feature: String? = null, val note: String = "")

@Serializable
data class EvalRequest(val id: String, val text: String, val modules: List<String> = emptyList(), val features: List<String> = emptyList())

@Serializable
data class EvalReference(val request: String, val section: String, val target: String, val required: Boolean, val forbidden: Boolean)

@Serializable
data class EvalDiff(val id: String, val request: String, val file: String, val stage: String = "IMPLEMENTATION", val violates: List<String> = emptyList(), val note: String = "")

@Serializable
data class EvalRule(val section: String, val kind: String, val params: Map<String, List<String>>, val description: String, val quote: String, val faithful: Boolean, val note: String = "")

@Serializable
data class GuidelineCorpus(
    val modules: List<String> = emptyList(),
    val documents: Map<String, EvalDocument>,
    val sections: Map<String, EvalSection> = emptyMap(),
    val requests: List<EvalRequest>,
    val references: List<EvalReference> = emptyList(),
    val diffs: List<EvalDiff> = emptyList(),
    val rules: List<EvalRule> = emptyList(),
    val structure: String = "",
)

@Serializable
data class Observation(val question: String, val project: String, val item: String, val expected: Boolean, val p: Double?, val round: Int)

object JevGuidelineEval {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }

    class Project(val name: String, val corpus: GuidelineCorpus, val sections: List<DocSection>, val diffs: Map<String, String>) {
        fun key(s: DocSection) = "${s.path}:${s.line}"
        fun label(s: DocSection): EvalSection? = corpus.sections[key(s)]
        fun module(s: DocSection): String? = label(s)?.module ?: corpus.documents[s.path]?.module
        fun feature(s: DocSection): String? = label(s)?.feature ?: corpus.documents[s.path]?.feature
        fun rules(path: String): Boolean? = when {
            sections.any { it.path == path && label(it)?.rule == true } -> true
            sections.any { it.path == path && label(it)?.rule == null } -> null
            else -> false
        }
        fun documentApplies(path: String, r: EvalRequest): Boolean =
            corpus.documents[path].let { d -> (d?.module == null || d.module in r.modules) && (d?.feature == null || d.feature in r.features) }
        fun sectionApplies(s: DocSection, r: EvalRequest): Boolean =
            module(s).let { it == null || it in r.modules } && feature(s).let { it == null || it in r.features }
        fun relevant(s: DocSection, r: EvalRequest): Boolean? {
            val rule = label(s)?.rule ?: return null
            return rule && rules(s.path) == true && documentApplies(s.path, r) && sectionApplies(s, r)
        }
        fun request(id: String): EvalRequest = corpus.requests.single { it.id == id }
    }

    class Question(val id: String, val title: String, val threshold: Double, val strict: Boolean, val goal: Goal)

    enum class Goal { RECALL, PRECISION }

    val questions = listOf(
        Question("w", "모듈 질문: 요청이 그 모듈을 바꾸는가", Guidelines.IRRELEVANT, true, Goal.RECALL),
        Question("k", "문서 거름: 규칙 문서인가", Guidelines.IRRELEVANT, true, Goal.RECALL),
        Question("m", "문서 거름: 요청 모듈·기능에 적용되는가", Guidelines.IRRELEVANT, true, Goal.RECALL),
        Question("r", "구간: 구체 규칙이 적혀 있는가", Guidelines.IRRELEVANT, true, Goal.RECALL),
        Question("s", "구간: 요청 코드에 적용되는가", Guidelines.IRRELEVANT, true, Goal.RECALL),
        Question("sel", "최종 선택(관련도)", Guidelines.IRRELEVANT, true, Goal.RECALL),
        Question("q", "참조: 그 문서를 읽으라고 하는가", Guidelines.APPLIES, false, Goal.RECALL),
        Question("n", "참조: 읽지 말라고 하는가", Guidelines.VIOLATED, false, Goal.PRECISION),
        Question("v", "위반 판정(정답 구간)", Guidelines.VIOLATED, false, Goal.PRECISION),
        Question("v-e2e", "위반 판정(실제 선택 구간)", Guidelines.VIOLATED, false, Goal.PRECISION),
        Question("v-first", "위반 판정(재질문 전, 항목 최댓값)", Guidelines.VIOLATED, false, Goal.PRECISION),
        Question("diff", "변경 차단(가장 높은 위반 확률)", Guidelines.VIOLATED, false, Goal.PRECISION),
        Question("diff-static", "변경 차단(승인한 문서 규칙 정적 검사)", Guidelines.VIOLATED, false, Goal.PRECISION),
        Question("diff-all", "변경 차단·검토(정적 문서 규칙과 JEV 중 큰 값)", Guidelines.VIOLATED, false, Goal.PRECISION),
        Question("gate", "문서 규칙: 규칙 구간 거름", Guidelines.CLEAR, false, Goal.RECALL),
        Question("f", "문서 규칙: 원문 그대로 옮겼는가", DocRules.FAITHFUL, false, Goal.PRECISION),
        Question("f-single", "문서 규칙: 원문 일치(후보마다 따로 판정)", DocRules.FAITHFUL, false, Goal.PRECISION),
    )

    fun load(dir: Path): List<Project> = dir.listDirectoryEntries().filter { it.resolve("case.json").exists() }.sortedBy { it.fileName.toString() }.map { root ->
        val corpus = json.decodeFromString<GuidelineCorpus>(root.resolve("case.json").readText())
        val sections = corpus.documents.flatMap { (path, doc) -> ProjectDocs.split(path, root.resolve(doc.file).readText()) }
        Project(root.fileName.toString(), corpus, sections, corpus.diffs.associate { it.id to root.resolve(it.file).readText() })
    }

    fun problems(p: Project): List<String> {
        val keys = p.sections.map(p::key).toSet()
        val out = mutableListOf<String>()
        p.sections.filter { p.label(it) == null }.forEach { out += "${p.name}: 정답이 없는 구간 ${p.key(it)} (${it.heading})" }
        (p.corpus.sections.keys - keys).forEach { out += "${p.name}: 없는 구간 키 $it" }
        val requests = p.corpus.requests.map { it.id }.toSet()
        p.corpus.diffs.forEach { d ->
            if (d.request !in requests) out += "${p.name}: ${d.id} 의 요청 ${d.request} 이 없습니다"
            d.violates.filter { p.corpus.sections[it]?.rule != true }.forEach { out += "${p.name}: ${d.id} 가 규칙이 아닌 구간 $it 을 어긴다고 적혀 있습니다" }
        }
        p.corpus.references.filter { it.section !in keys || it.request !in requests }.forEach { out += "${p.name}: 참조 사례 ${it.section} → ${it.target} 의 구간·요청이 없습니다" }
        p.corpus.rules.forEach { r ->
            if (r.section !in keys) out += "${p.name}: 문서 규칙 사례의 구간 ${r.section} 이 없습니다"
            runCatching { DocProbes.normalize(r.kind, r.params) }.onFailure { out += "${p.name}: 문서 규칙 사례 형식 오류(${r.description}): ${it.message}" }
        }
        return out
    }

    fun sections(p: Project): String = buildString {
        appendLine("[${p.name}] 구간 ${p.sections.size}개")
        p.sections.forEach { s ->
            val label = p.label(s)
            val mark = when (label?.rule) { true -> "규칙"; false -> "아님"; null -> if (label == null) "정답 없음" else "애매" }
            appendLine("  ${p.key(s)} [$mark] ${s.heading} | ${s.text.lines().drop(1).firstOrNull { it.isNotBlank() }?.trim()?.take(70).orEmpty()}")
        }
    }

    fun run(projects: List<Project>, rounds: Int, rulesOnly: Boolean = false, jev: (Int) -> Jev): List<Observation> = (1..rounds).flatMap { round ->
        val client = jev(round)
        projects.flatMap { observe(it, client, round, rulesOnly) }
    }

    private fun observe(p: Project, jev: Jev, round: Int, rulesOnly: Boolean): List<Observation> {
        val out = mutableListOf<Observation>()
        fun add(question: String, item: String, expected: Boolean, value: Double?) { out += Observation(question, p.name, item, expected, value, round) }
        val pool = Executors.newFixedThreadPool(Guidelines.PARALLEL)
        try {
            val modules = p.corpus.modules.toSet()
            val selections = p.corpus.requests.filter { !rulesOnly }.associate { r ->
                val touched = Guidelines.touched(jev, r.text, modules).orEmpty()
                modules.forEach { m -> add("w", "${r.id}/$m", m in r.modules, touched[m]) }
                val documents = Guidelines.documentAnswers(jev, r.text, p.sections, pool).orEmpty()
                documents.forEach { (path, km) ->
                    p.rules(path)?.let { add("k", "${r.id}/$path", it, km.first) }
                    add("m", "${r.id}/$path", p.documentApplies(path, r), km.second)
                }
                val raw = Guidelines.sectionAnswers(jev, r.text, p.sections, pool).orEmpty()
                p.sections.forEach { s ->
                    val label = p.label(s)?.rule ?: return@forEach
                    val rs = raw[s]
                    add("r", "${r.id}/${p.key(s)}", label, rs?.first)
                    if (label) add("s", "${r.id}/${p.key(s)}", p.sectionApplies(s, r), rs?.second)
                }
                val selection = Guidelines.selection(jev, r.text, p.sections, modules)
                p.sections.forEach { s ->
                    val expected = p.relevant(s, r) ?: return@forEach
                    add("sel", "${r.id}/${p.key(s)}", expected, selection?.guidelines?.firstOrNull { it.path == s.path && it.line == s.line }?.relevance)
                }
                r.id to selection
            }
            val paths = p.sections.map { it.path }.toSet()
            p.corpus.references.filter { !rulesOnly }.forEach { ref ->
                val section = p.sections.single { p.key(it) == ref.section }
                val g = Guideline(section.path, section.line, section.heading, section.text, 1.0)
                val targets = (Guidelines.links(section.path, section.text, paths + ref.target) + ref.target).distinct()
                val answer = Guidelines.referenceAnswers(jev, p.request(ref.request).text, g, targets)?.get(ref.target)
                add("q", "${ref.request}/${ref.section}->${ref.target}", ref.required, answer?.first)
                add("n", "${ref.request}/${ref.section}->${ref.target}", ref.forbidden, answer?.second)
            }
            p.corpus.diffs.filter { !rulesOnly }.forEach { d ->
                val request = p.request(d.request)
                val diff = p.diffs.getValue(d.id)
                val selected = selections[d.request]?.guidelines?.let(Guidelines::relevant).orEmpty()
                val e2e = Guidelines.check(jev, d.stage, selected, diff)
                selected.forEach { g ->
                    val label = p.corpus.sections["${g.path}:${g.line}"]?.rule ?: return@forEach
                    add("v-e2e", "${d.id}/${g.source}", label && g.source in d.violates, e2e?.probabilities?.get(g))
                }
                val jevScore = e2e?.let { c -> selected.mapNotNull { c.probabilities[it] }.maxOrNull() ?: 0.0 }
                add("diff", d.id, d.violates.isNotEmpty(), jevScore)
                val caught = if (staticCatch(diff, approvedRules(p))) 1.0 else 0.0
                add("diff-static", d.id, d.violates.isNotEmpty(), caught)
                add("diff-all", d.id, d.violates.isNotEmpty(), jevScore?.let { maxOf(it, caught) })
                d.violates.filter { v -> selected.none { it.source == v } }.forEach { add("missed-selection", "${d.id}/$it", true, 0.0) }
                val gold = p.sections.filter { p.relevant(it, request) == true }.map { Guideline(it.path, it.line, it.heading, it.text, 1.0) }
                val check = Guidelines.check(jev, d.stage, gold, diff)
                gold.forEach { g ->
                    val expected = g.source in d.violates
                    add("v", "${d.id}/${g.source}", expected, check?.probabilities?.get(g))
                    val parts = check?.parts.orEmpty().filter { it.guideline == g }
                    add("v-first", "${d.id}/${g.source}", expected, parts.mapNotNull { it.batch }.maxOrNull())
                    parts.forEachIndexed { i, part ->
                        add("part-batch", "${d.id}/${g.source}#$i", expected, part.batch)
                        part.single?.let { add("part-single", "${d.id}/${g.source}#$i", expected, it) }
                    }
                }
            }
            if (p.corpus.rules.isNotEmpty() || p.sections.isNotEmpty()) {
                val gate = Guidelines.ruleScores(jev, p.sections).orEmpty()
                p.sections.forEach { s ->
                    val label = p.label(s)?.rule ?: return@forEach
                    add("gate", p.key(s), label && p.rules(s.path) == true, gate[s])
                }
            }
            val proposals = p.corpus.rules.map { r ->
                val section = p.sections.single { p.key(it) == r.section }
                r to DocRules.Proposal(section, r.kind, DocProbes.normalize(r.kind, r.params), r.description, r.quote)
            }
            val faith = DocRules.verify(jev, proposals.map { it.second }, p.corpus.structure)
            val single = DocRules.verify(jev, proposals.map { it.second }, p.corpus.structure, single = true)
            proposals.forEach { (r, proposal) ->
                add("f", "${r.section}/${r.description}", r.faithful, faith?.get(proposal))
                add("f-single", "${r.section}/${r.description}", r.faithful, single?.get(proposal))
            }
        } finally {
            pool.shutdownNow()
        }
        return out
    }

    class Score(val n: Int, val positives: Int, val tp: Int, val fp: Int, val fn: Int, val tn: Int, val missing: Int) {
        val accuracy get() = if (n - missing == 0) null else (tp + tn).toDouble() / (n - missing)
        val recall get() = if (tp + fn == 0) null else tp.toDouble() / (tp + fn)
        val precision get() = if (tp + fp == 0) null else tp.toDouble() / (tp + fp)
    }

    fun score(obs: List<Observation>, threshold: Double, strict: Boolean): Score {
        fun positive(p: Double) = if (strict) p > threshold else p >= threshold
        val known = obs.filter { it.p != null }
        return Score(
            obs.size, obs.count { it.expected },
            known.count { it.expected && positive(it.p!!) }, known.count { !it.expected && positive(it.p!!) },
            known.count { it.expected && !positive(it.p!!) }, known.count { !it.expected && !positive(it.p!!) },
            obs.count { it.p == null },
        )
    }

    private val sweep = (1..19).map { it * 5 / 100.0 }

    fun suggest(obs: List<Observation>, q: Question): Double? = when (q.goal) {
        Goal.RECALL -> sweep.filter { score(obs, it, q.strict).fn == 0 }.maxOrNull()
        Goal.PRECISION -> sweep.filter { score(obs, it, q.strict).fp == 0 }.minOrNull()
    }

    private fun approvedRules(p: Project): List<com.dochiri.ilsim.runner.convention.ConventionRule> = toRules(p.corpus.rules.filter { it.faithful })

    private fun toRules(rules: List<EvalRule>): List<com.dochiri.ilsim.runner.convention.ConventionRule> = rules.mapIndexed { i, r ->
        com.dochiri.ilsim.runner.convention.ConventionRule("doc:eval$i", r.kind, r.description, com.dochiri.ilsim.runner.convention.DocProbes.normalize(r.kind, r.params), approved = true)
    }

    internal fun staticCatch(diff: String, rules: List<com.dochiri.ilsim.runner.convention.ConventionRule>): Boolean {
        if (rules.isEmpty()) return false
        val files = ScopeCheck.files(diff).mapNotNull { (path, text) ->
            val ext = path.substringAfterLast('.', "")
            if (ext !in setOf("java", "kt")) return@mapNotNull null
            val added = text.lines().filter { it.startsWith("+") && !it.startsWith("+++") }.map { it.drop(1) }
            val wrapped = if (added.any { Regex("""^\s*(public\s+|internal\s+)?(final\s+|abstract\s+|data\s+)?(class|interface|object|enum|record)\s""").containsMatchIn(it) }) added
            else listOf("class ${baseName(path)} {") + added + "}"
            val packaged = if (wrapped.any { it.trimStart().startsWith("package ") }) wrapped else listOf("package eval${if (ext == "java") ";" else ""}") + wrapped
            com.dochiri.ilsim.runner.convention.FactScanner.parse(path, ext, com.dochiri.ilsim.runner.Gate.isTestPath(path), packaged)
        }
        if (files.isEmpty()) return false
        val model = com.dochiri.ilsim.runner.convention.ConventionModel(null, 0, 0, emptyMap(), rules)
        return com.dochiri.ilsim.runner.convention.ConventionChecker.check(com.dochiri.ilsim.runner.convention.Facts(files), model, approvedOnly = true).isNotEmpty()
    }

    private fun baseName(path: String) = path.substringAfterLast('/').substringBeforeLast('.')

    fun report(projects: List<Project>, observations: List<Observation>): String = buildString {
        val first = observations.filter { it.round == 1 }
        appendLine("JEV 지침·문서 규칙 판정 평가: 프로젝트 ${projects.size}개(${projects.joinToString { it.name }}), 구간 ${projects.sumOf { it.sections.size }}개, 요청 ${projects.sumOf { it.corpus.requests.size }}개, 변경 ${projects.sumOf { it.corpus.diffs.size }}개, 문서 규칙 후보 ${projects.sumOf { it.corpus.rules.size }}개, 반복 ${observations.maxOfOrNull { it.round } ?: 0}회")
        appendLine()
        observations.filter { it.question == "diff-all" && it.p != null }.groupBy { it.project to it.item }.values.let { groups ->
            val bad = groups.filter { it.first().expected }
            val good = groups.filter { !it.first().expected }
            val static = observations.filter { it.question == "diff-static" && it.expected && (it.p ?: 0.0) >= 1.0 }.map { it.project to it.item }.toSet()
            appendLine("변경 단위 종합(정적 문서 규칙+JEV, 모든 반복 기준): 위반 ${bad.size}개 중 막음 ${bad.count { g -> g.all { it.p!! >= Guidelines.VIOLATED } }}(정적 ${static.size}), 검토로 올림 ${bad.count { g -> g.any { it.p!! < Guidelines.VIOLATED } && g.all { it.p!! > Guidelines.CLEAR } }}, 조용히 통과 ${bad.count { g -> g.any { it.p!! <= Guidelines.CLEAR } }} / 정상 ${good.size}개 중 잘못 막음 ${good.count { g -> g.any { it.p!! >= Guidelines.VIOLATED } }}, 검토로 올림 ${good.count { g -> g.any { it.p!! > Guidelines.CLEAR && it.p!! < Guidelines.VIOLATED } }}")
            bad.filter { g -> g.any { it.p!! <= Guidelines.CLEAR } }.forEach { g -> appendLine("  조용히 통과: ${g.first().project}/${g.first().item} ${g.joinToString("/") { "%.2f".format(it.p) }}") }
            appendLine()
        }
        appendLine("질문 | 사례(양성) | AUROC | 현재 기준 | 정확도 | 놓침 | 오경보 | 판정 실패 | 제안 기준(놓침 0 또는 오경보 0)")
        for (q in questions) {
            val obs = first.filter { it.question == q.id }
            if (obs.isEmpty()) continue
            val s = score(obs, q.threshold, q.strict)
            val known = obs.filter { it.p != null }
            val auroc = JevRiskEval.auroc(known.filter { it.expected }.map { it.p!! }, known.filter { !it.expected }.map { it.p!! })
            val proposal = suggest(obs, q)?.let { t -> score(obs, t, q.strict).let { "${"%.2f".format(t)} (놓침 ${it.fn}·오경보 ${it.fp})" } } ?: "없음"
            appendLine("${q.id} ${q.title} | ${obs.size}(${s.positives}) | ${auroc?.let { "%.2f".format(it) } ?: "-"} | ${if (q.strict) ">" else "≥"}${"%.2f".format(q.threshold)} | ${pct(s.accuracy)} | ${s.fn} | ${s.fp} | ${s.missing} | $proposal")
        }
        first.filter { it.question == "missed-selection" }.takeIf { it.isNotEmpty() }?.let { missed ->
            appendLine()
            appendLine("선택에서 빠져 위반을 볼 수 없었던 구간 ${missed.size}개: ${missed.joinToString { "${it.project}/${it.item}" }}")
        }
        appendLine()
        appendLine("프로젝트별 | ${listOf("sel", "v", "v-e2e", "diff", "f").joinToString(" | ") { "$it 놓침/오경보" }}")
        for (p in projects) {
            val cells = listOf("sel", "v", "v-e2e", "diff", "f").map { id ->
                val q = questions.single { it.id == id }
                val obs = first.filter { it.project == p.name && it.question == id }
                if (obs.isEmpty()) "-" else score(obs, q.threshold, q.strict).let { "${it.fn}/${it.fp}" }
            }
            appendLine("${p.name} | ${cells.joinToString(" | ")}")
        }
        val rounds = observations.maxOfOrNull { it.round } ?: 1
        if (rounds > 1) {
            appendLine()
            appendLine("반복 안정성(같은 사례의 확률 최대-최소, 현재 기준으로 판정이 바뀐 사례 수):")
            for (q in questions) {
                val groups = observations.filter { it.question == q.id && it.p != null }.groupBy { "${it.project}/${it.item}" }.values.filter { it.size > 1 }
                if (groups.isEmpty()) continue
                val spread = groups.map { g -> g.maxOf { it.p!! } - g.minOf { it.p!! } }
                val flips = groups.count { g -> g.map { if (q.strict) it.p!! > q.threshold else it.p!! >= q.threshold }.distinct().size > 1 }
                appendLine("  ${q.id}: 평균 ${"%.3f".format(spread.average())}, 최대 ${"%.3f".format(spread.max())}, 판정 바뀜 ${flips}/${groups.size}")
            }
        }
        appendLine()
        appendLine("현재 기준으로 틀린 사례:")
        for (q in questions) {
            val wrong = first.filter { it.question == q.id && it.p != null }.filter { o ->
                val positive = if (q.strict) o.p!! > q.threshold else o.p!! >= q.threshold
                positive != o.expected
            }
            wrong.sortedByDescending { kotlin.math.abs(it.p!! - q.threshold) }.forEach { o ->
                appendLine("  ${q.id} ${if (o.expected) "놓침" else "오경보"} ${"%.2f".format(o.p)} ${o.project}/${o.item}")
            }
        }
    }

    private fun pct(v: Double?) = v?.let { "${(it * 100).roundToInt()}%" } ?: "-"

    fun toJson(observations: List<Observation>): String = json.encodeToString(observations)
}
