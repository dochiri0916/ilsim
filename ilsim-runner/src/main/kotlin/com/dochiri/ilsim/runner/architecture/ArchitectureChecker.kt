package com.dochiri.ilsim.runner.architecture

import com.dochiri.ilsim.runner.profile.ProjectProfile
import java.nio.file.Path

object ArchitectureChecker {

    fun check(root: Path, profile: ProjectProfile, model: ArchitectureModel, approvedOnly: Boolean = false): List<Violation> =
        check(SourceScanner.scan(root, profile.modules), model, approvedOnly)

    internal fun check(files: List<SourceFile>, model: ArchitectureModel, approvedOnly: Boolean = false): List<Violation> {
        val prefix = model.layout.rootPrefix
        require(prefix.isNotEmpty() && files.any { it.pkg == prefix || it.pkg.startsWith("$prefix.") } || prefix.isEmpty() && files.isNotEmpty()) {
            "이 규칙 모델은 다른 프로젝트용입니다 (rootPrefix=`$prefix`, 프로젝트=${model.projectName})"
        }
        val locator = Locator(model.layout)
        val a = model.applicability
        val deps = Dependencies.resolve(files, locator)
            .filterNot { it.from.slice in a.unverifiedSlices || it.from.unit in a.unverifiedUnits }
        val out = mutableListOf<Violation>()
        for (rule in model.rules.filter { !it.rejected && (!approvedOnly || it.approved) }) out += evaluate(rule, deps, model)
        return out.sortedWith(compareBy({ it.file }, { it.line }, { it.ruleId }))
    }

    private fun v(rule: ArchRule, d: Dep, msg: String) = Violation(rule.id, rule.proposedSeverity, d.file.path, d.line, msg)

    private fun confined(rolePath: String, allowed: String): Boolean {
        val path = if (allowed.startsWith("*.")) rolePath.substringAfter('.', "") else rolePath
        val prefix = allowed.removePrefix("*.")
        return path == prefix || path.startsWith("$prefix.")
    }

    private fun evaluate(rule: ArchRule, deps: List<Dep>, model: ArchitectureModel): List<Violation> {
        val p = rule.params
        return when (rule.kind) {
            "no-cycles" -> cycleViolations(rule, deps, model)
            "forbid-edge", "absent-edge" -> deps.filter { it.isUnitEdge() && it.from.unit == p["from"]!![0] && it.to!!.unit == p["to"]!![0] }
                .map { v(rule, it, "금지된 의존: `${it.from.unit}` → `${it.to!!.unit}` (${it.name})") }
            "pure-layer" -> deps.filter { it.external != null && it.from.unit == p["layer"]!![0] }
                .map { v(rule, it, "`${it.from.unit}` 은 외부 라이브러리를 import 하지 않아야 합니다: ${it.name}") }
            "layer-externals" -> deps.filter { it.external != null && it.from.unit == p["layer"]!![0] && it.external !in p["allowed"]!! }
                .map { v(rule, it, "`${it.from.unit}` 에서 허용되지 않은 외부 라이브러리: ${it.external} (${it.name})") }
            "leaf-unit" -> deps.filter { it.to != null && it.from.unit == p["unit"]!![0] && (it.isUnitEdge() || it.isCrossSlice()) }
                .map { v(rule, it, "`${it.from.unit}` 은 다른 단위에 의존하지 않아야 합니다: ${it.name}") }
            "confinement" -> {
                val allowed = p["confinedTo"]!!
                deps.filter { d -> d.external == p["prefix"]!![0] && d.from.unit !in pseudoUnits && allowed.none { confined(d.from.rolePath, it) } }
                    .map { v(rule, it, "`${it.external}` 는 ${allowed.joinToString { a -> "`$a`" }} 안에서만 사용해야 합니다 (현재 위치: `${it.from.rolePath.ifEmpty { it.from.unit }}`)") }
            }
            "shared-independence" -> deps.filter { it.to != null && it.from.slice == null && it.from.unit.startsWith("shared:") && it.to.slice != null }
                .map { v(rule, it, "공유 단위가 슬라이스 `${it.to!!.slice}` 에 의존합니다: ${it.name}") }
            "cross-slice-targets" -> deps.filter { it.isCrossSlice() && "${it.from.unit}>${it.to!!.unit}" !in p["allowed"]!! }
                .map { v(rule, it, "관측되지 않은 슬라이스 간 의존: ${it.from.slice}.${it.from.unit} → ${it.to!!.slice}.${it.to.unit} (${it.name})") }
            else -> emptyList()
        }
    }

    private fun cycleViolations(rule: ArchRule, deps: List<Dep>, model: ArchitectureModel): List<Violation> {
        val slice = rule.params["scope"]?.firstOrNull() == "slice"
        val scoped = if (slice) deps.filter { it.isCrossSlice() } else deps.filter { it.isUnitEdge() }
        val key: (Dep) -> Pair<String, String> = if (slice) { d -> d.from.slice!! to d.to!!.slice!! } else { d -> d.from.unit to d.to!!.unit }
        val known = (if (slice) model.sliceEdges else model.layerEdges).map { it.from to it.to }.toSet()
        val cycles = Graphs.cycles(scoped.map(key).toSet())
        if (cycles.isEmpty()) return emptyList()
        return scoped.filter { d ->
            val (a, b) = key(d)
            cycles.any { a in it && b in it } && (a to b) !in known
        }.map { v(rule, it, "순환 의존을 만드는 의존: ${key(it).first} → ${key(it).second} (${it.name})") }
    }
}
