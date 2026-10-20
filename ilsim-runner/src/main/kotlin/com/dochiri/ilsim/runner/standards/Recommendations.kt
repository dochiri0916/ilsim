package com.dochiri.ilsim.runner.standards

import com.dochiri.ilsim.runner.architecture.ArchRule
import com.dochiri.ilsim.runner.architecture.ArchitectureChecker
import com.dochiri.ilsim.runner.architecture.ArchitectureModel
import com.dochiri.ilsim.runner.architecture.Dependencies
import com.dochiri.ilsim.runner.architecture.Evidence
import com.dochiri.ilsim.runner.architecture.Graphs
import com.dochiri.ilsim.runner.architecture.Locator
import com.dochiri.ilsim.runner.architecture.Recommendation
import com.dochiri.ilsim.runner.architecture.SourceFile
import com.dochiri.ilsim.runner.architecture.SourceScanner
import com.dochiri.ilsim.runner.architecture.isCrossSlice
import com.dochiri.ilsim.runner.architecture.isUnitEdge
import com.dochiri.ilsim.runner.convention.ConventionModel
import com.dochiri.ilsim.runner.convention.ConventionRule
import com.dochiri.ilsim.runner.convention.FactScanner
import com.dochiri.ilsim.runner.convention.Facts
import com.dochiri.ilsim.runner.convention.Probes
import com.dochiri.ilsim.runner.profile.ProjectProfile
import java.nio.file.Path

object Recommendations {
    const val PREFIX = "recommend:"

    fun isRecommended(id: String) = id.startsWith(PREFIX)
}

object ArchitectureRecommender {
    private val domainUnits = setOf("domain", "core")
    private val applicationUnits = setOf("application", "usecase", "usecases", "service", "services")
    private val adapterUnits = setOf("adapter", "adapters", "infrastructure", "infra", "persistence", "web", "controller", "controllers", "presentation", "repository")

    private val dependencyRule = Recommendation(
        "클린 아키텍처·헥사고날",
        "의존은 바깥(어댑터)에서 안(애플리케이션→도메인)으로만 향한다. 도메인이 바깥을 모르면 프레임워크·저장소를 바꿔도 핵심 규칙이 그대로다",
    )
    private val portRule = Recommendation(
        "헥사고날(포트와 어댑터)",
        "애플리케이션은 포트(인터페이스)에만 의존하고 어댑터는 그 포트를 구현한다. 유스케이스를 어댑터 없이 테스트할 수 있다",
    )
    private val pureDomain = Recommendation(
        "DDD·클린 아키텍처",
        "도메인 모델은 프레임워크·라이브러리 없이 순수하게 둔다. 비즈니스 규칙이 기술 변화에 휘둘리지 않는다",
    )
    private val acyclic = Recommendation(
        "DDD(경계 컨텍스트)·비순환 의존 원칙",
        "순환 의존은 함께 바꿔야 하는 덩어리를 만든다. 경계 사이 의존을 한 방향으로 정리한다",
    )
    private val sharedKernel = Recommendation(
        "DDD(공유 커널)",
        "공유 코드는 특정 경계 컨텍스트를 알지 않아야 모든 컨텍스트가 안심하고 쓴다",
    )

    fun recommend(root: Path, profile: ProjectProfile, model: ArchitectureModel): List<ArchRule> =
        recommend(SourceScanner.scan(root, profile.modules), model)

    internal fun recommend(files: List<SourceFile>, model: ArchitectureModel): List<ArchRule> {
        if (model.layout.mode == "UNKNOWN" || files.isEmpty()) return emptyList()
        val locator = Locator(model.layout)
        val units = files.map { locator.locate(it.pkg).unit }.toSet()
        val domain = units.filter { it in domainUnits }.sorted()
        val application = units.filter { it in applicationUnits }.sorted()
        val adapter = units.filter { it in adapterUnits }.sorted()
        val candidates = buildList {
            for (d in domain) for (x in application + adapter) add(edge(d, x, dependencyRule))
            for (a in application) for (x in adapter) add(edge(a, x, portRule))
            for (d in domain) add(rule("pure-layer:$d", "pure-layer", "`$d` 은 프레임워크·외부 라이브러리를 import 하지 않는다", mapOf("layer" to listOf(d)), pureDomain))
            add(rule("no-cycles:unit", "no-cycles", "단위(레이어) 간 순환 의존이 없다", mapOf("scope" to listOf("unit")), acyclic))
            if (model.layout.mode == "SLICED") {
                add(rule("no-cycles:slice", "no-cycles", "슬라이스(경계 컨텍스트) 간 순환 의존이 없다", mapOf("scope" to listOf("slice")), acyclic))
                add(rule("shared-independence", "shared-independence", "공유 단위는 슬라이스에 의존하지 않는다", emptyMap(), sharedKernel))
            }
        }
        val adopted = model.rules.filter { !Recommendations.isRecommended(it.id) }
            .map { (if (it.kind == "absent-edge") "forbid-edge" else it.kind) to it.params }.toSet()
        return candidates.filter { (it.kind to it.params) !in adopted }.mapNotNull { evaluate(it, files, model, locator) }
    }

    private fun edge(from: String, to: String, why: Recommendation) =
        rule("forbid-edge:$from>$to", "forbid-edge", "`$from` 은 `$to` 에 의존하지 않는다", mapOf("from" to listOf(from), "to" to listOf(to)), why)

    private fun rule(id: String, kind: String, description: String, params: Map<String, List<String>>, why: Recommendation) =
        ArchRule(Recommendations.PREFIX + id, kind, description, params, "REVIEW", Evidence(0, 0), recommendation = why)

    private fun evaluate(rule: ArchRule, files: List<SourceFile>, model: ArchitectureModel, locator: Locator): ArchRule? {
        val scope = when (rule.kind) {
            "forbid-edge" -> files.filter { locator.locate(it.pkg).unit == rule.params.getValue("from").first() }
            "pure-layer" -> files.filter { locator.locate(it.pkg).unit == rule.params.getValue("layer").first() }
            "shared-independence" -> files.filter { locator.locate(it.pkg).unit.startsWith("shared:") }
            else -> files
        }
        if (scope.isEmpty()) return null
        if (rule.kind == "no-cycles" && !hasEdges(rule, files, locator)) return null
        val violating = if (rule.kind == "no-cycles") cycleFiles(rule, files, locator)
        else ArchitectureChecker.check(files, model.copy(rules = listOf(rule))).map { it.file to it.line }
        val bad = violating.map { it.first }.toSet()
        val total = scope.size
        val ok = scope.count { it.path !in bad }
        val severity = if (bad.isEmpty()) "BLOCK" else "REVIEW"
        return rule.copy(
            description = "${rule.description} (현재 준수: $ok/$total 파일)",
            proposedSeverity = severity,
            evidence = Evidence(ok, total, violating.take(3).map { "${it.first}:${it.second}" }),
        )
    }

    private fun scoped(rule: ArchRule, files: List<SourceFile>, locator: Locator): List<com.dochiri.ilsim.runner.architecture.Dep> {
        val slice = rule.params["scope"]?.firstOrNull() == "slice"
        return Dependencies.resolve(files, locator).filter { if (slice) it.isCrossSlice() else it.isUnitEdge() }
    }

    private fun hasEdges(rule: ArchRule, files: List<SourceFile>, locator: Locator) = scoped(rule, files, locator).isNotEmpty()

    private fun cycleFiles(rule: ArchRule, files: List<SourceFile>, locator: Locator): List<Pair<String, Int>> {
        val slice = rule.params["scope"]?.firstOrNull() == "slice"
        val deps = scoped(rule, files, locator)
        val key = { d: com.dochiri.ilsim.runner.architecture.Dep -> if (slice) d.from.slice!! to d.to!!.slice!! else d.from.unit to d.to!!.unit }
        val cycles = Graphs.cycles(deps.map(key).toSet())
        return deps.filter { d -> val (a, b) = key(d); cycles.any { a in it && b in it } }.map { it.file.path to it.line }
    }
}

object ConventionRecommender {
    private class Spec(val kind: String, val description: String, val why: Recommendation, val params: Map<String, List<String>> = emptyMap(), val kotlinOnly: Boolean = false)

    private val specs = listOf(
        Spec("no-comments", "주석을 쓰지 않는다(요청이 있을 때만 쓴다)",
            Recommendation("클린 코드", "주석은 코드와 쉽게 어긋난다. 이름·작은 함수·타입으로 의도를 드러낸다")),
        Spec("domain-encapsulation", "도메인 객체는 공개 var·setter 로 상태를 바꾸지 않는다",
            Recommendation("객체지향·DDD·생활 체조 9", "묻지 말고 시켜라: 상태 변경은 규칙을 지키는 도메인 메서드로만 한다. 불변식이 한곳에서 지켜진다")),
        Spec("identifier-value-object", "도메인 식별자는 원시 타입 대신 값 객체로 감싼다",
            Recommendation("DDD·생활 체조 3(원시값 포장)", "OrderId·UserId 처럼 타입으로 구분하면 식별자를 바꿔 넣는 실수를 컴파일러가 막는다")),
        Spec("max-function-lines", "함수는 30줄을 넘지 않는다",
            Recommendation("클린 코드·생활 체조 7", "함수는 한 가지 일만 한다. 짧으면 이름이 곧 설명이 되고 테스트가 쉬워진다"), mapOf("max" to listOf("30"))),
        Spec("max-nesting", "함수 안 중첩 블록은 2단계를 넘지 않는다",
            Recommendation("생활 체조 1·2", "들여쓰기를 줄이고 else 대신 조기 반환·작은 함수로 흐름을 편다"), mapOf("max" to listOf("2"))),
        Spec("max-file-lines", "파일은 300줄을 넘지 않는다",
            Recommendation("단일 책임 원칙·생활 체조 7", "큰 클래스는 여러 책임이 섞인 신호다. 책임별로 나눈다"), mapOf("max" to listOf("300"))),
        Spec("no-field-injection", "필드 주입 대신 생성자로 주입한다",
            Recommendation("객체지향(의존성 역전)", "생성자 주입은 의존을 드러내고 불변·테스트 가능하게 만든다")),
        Spec("no-debug-output", "System.out·println·printStackTrace 대신 로거를 쓴다",
            Recommendation("클린 코드", "운영에서 추적 가능한 로그만 남긴다")),
        Spec("no-wildcard-import", "와일드카드 import 를 쓰지 않는다",
            Recommendation("클린 코드", "어떤 타입에 의존하는지 import 만 보고 알 수 있다")),
        Spec("no-force-unwrap", "Kotlin 에서 `!!` 를 쓰지 않는다",
            Recommendation("Kotlin 안전성", "널 가능성을 타입과 명시적 처리로 드러내 런타임 NPE 를 막는다"), kotlinOnly = true),
    )

    fun recommend(root: Path, profile: ProjectProfile, model: ConventionModel): List<ConventionRule> =
        recommend(FactScanner.scan(root, profile.modules), model)

    internal fun recommend(facts: Facts, model: ConventionModel): List<ConventionRule> {
        val adopted = model.rules.filter { !Recommendations.isRecommended(it.id) }.map { it.kind }.toSet()
        return specs.filter { it.kind !in adopted }.mapNotNull { spec ->
            if (spec.kotlinOnly && facts.main.none { it.lang == "kotlin" }) return@mapNotNull null
            val probe = Probes.byKind(spec.kind) ?: return@mapNotNull null
            val samples = probe.samples(facts, spec.params)
            if (samples.isEmpty()) return@mapNotNull null
            val ok = samples.count { it.ok }
            ConventionRule(
                id = Recommendations.PREFIX + spec.kind,
                kind = spec.kind,
                description = "${spec.description} (현재 준수: $ok/${samples.size})",
                params = spec.params,
                severity = if (ok == samples.size) "BLOCK" else "REVIEW",
                evidence = Evidence(ok, samples.size, samples.filter { !it.ok }.take(3).map { "${it.file}:${it.line}" }),
                recommendation = spec.why,
            )
        }
    }
}

object RecommendationReport {
    fun architecture(rules: List<ArchRule>): String =
        render(rules.filter { Recommendations.isRecommended(it.id) }.map { Line(it.id, it.description, it.recommendation, it.evidence, it.proposedSeverity, it.approved, it.rejected) })

    fun convention(rules: List<ConventionRule>): String =
        render(rules.filter { Recommendations.isRecommended(it.id) }.map { Line(it.id, it.description, it.recommendation, it.evidence, it.severity, it.approved, it.rejected) })

    private class Line(val id: String, val description: String, val why: Recommendation?, val evidence: Evidence, val severity: String, val approved: Boolean, val rejected: Boolean)

    private fun render(lines: List<Line>): String = buildString {
        if (lines.isEmpty()) return@buildString
        appendLine()
        appendLine("추천 규칙 ${lines.size}건 (승인하면 적용, 위반이 있으면 업그레이드 작업으로 고친다):")
        for (l in lines) {
            val state = when { l.approved -> "승인됨"; l.rejected -> "거절됨"; else -> "제안" }
            appendLine("  [$state·${l.severity}] ${l.description}  <${l.id}>")
            l.why?.let { appendLine("      ${it.principle}: ${it.rationale}") }
            if (l.evidence.examples.isNotEmpty()) appendLine("      예: ${l.evidence.examples.joinToString()}")
        }
    }
}
