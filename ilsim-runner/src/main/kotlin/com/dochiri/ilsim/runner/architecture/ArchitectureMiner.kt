package com.dochiri.ilsim.runner.architecture

import com.dochiri.ilsim.runner.profile.ProjectProfile
import java.nio.file.Path

internal fun Dep.isCrossSlice() = to != null && from.slice != null && to.slice != null && from.slice != to.slice

internal fun Dep.isUnitEdge() = to != null && !isCrossSlice() && from.unit != to.unit && from.unit !in pseudoUnits && to.unit !in pseudoUnits

internal val pseudoUnits = setOf("(root)", "(slice-root)", "(outside)")

object ArchitectureMiner {

    fun mine(root: Path, profile: ProjectProfile): ArchitectureModel =
        mine(SourceScanner.scan(root, profile.modules), profile.name)

    internal fun mine(files: List<SourceFile>, name: String?): ArchitectureModel {
        var layout = LayoutInference.infer(files.map { it.pkg })
        val notes = mutableListOf<String>()
        if (layout.mode == "UNKNOWN") {
            notes += "패키지 구조에서 아키텍처 단위를 추론하지 못했습니다"
            return ArchitectureModel(name, files.size, layout, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), notes,
                ApplicabilityAssessor.notApplicable(notes.last()))
        }
        var locator = Locator(layout)
        var deps = Dependencies.resolve(files, locator)
        val kernels = sharedKernels(deps, layout)
        if (kernels.isNotEmpty()) {
            layout = layout.copy(slices = layout.slices - kernels.toSet(), shared = kernels)
            locator = Locator(layout)
            deps = Dependencies.resolve(files, locator)
        }

        val unitEdgeDeps = deps.filter { it.isUnitEdge() }
        val crossDeps = deps.filter { it.isCrossSlice() }
        val unitEdges = edgeStats(unitEdgeDeps) { it.from.unit to it.to!!.unit }
        val sliceEdges = edgeStats(crossDeps) { it.from.slice!! to it.to!!.slice!! }
        val unitPairs = unitEdges.map { it.from to it.to }.toSet()

        val unitFiles = files.groupBy { locator.locate(it.pkg).unit }.filterKeys { it !in pseudoUnits }
        val externalsByUnit = deps.filter { it.external != null && it.from.unit !in pseudoUnits }
            .groupBy({ it.from.unit }, { it.external!! }).mapValues { it.value.toSortedSet().toList() }
        val layers = unitFiles.map { (u, fs) -> LayerInfo(u, fs.size, externalsByUnit[u].orEmpty()) }.sortedBy { it.name }

        val levels = Graphs.levels(layers.map { it.name }, unitPairs.filter { it.first in unitFiles && it.second in unitFiles })
        if (levels == null) notes += "단위 간 순환 의존이 있어 의존 수준을 정할 수 없습니다"
        val outgoing = (unitEdgeDeps + crossDeps).map { it.from.unit }.toSet()
        val leafUnits = unitFiles.filter { (u, fs) -> fs.isNotEmpty() && u !in outgoing }.keys

        val rules = mutableListOf<ArchRule>()
        rules += cycleRules(unitPairs, sliceEdges, layout, notes)
        rules += leafRules(unitFiles, leafUnits)
        rules += directionRules(unitEdges, unitPairs, leafUnits)
        rules += absentRules(unitFiles, unitPairs, leafUnits, notes)
        rules += externalRules(unitFiles, externalsByUnit, deps)
        rules += confinementRules(deps, unitFiles.size)
        rules += sharedRules(deps, layout)
        rules += crossSliceRules(crossDeps)

        if (layout.mode == "FLAT" && layers.size < 2) notes += "독립된 단위가 하나뿐이라 구조 규칙을 만들 수 없습니다"
        val applicability = ApplicabilityAssessor.assess(
            files, layout, locator, layers.map { it.name }, unitPairs, sliceEdges.map { it.from to it.to }.toSet(), rules.size,
        )
        return ArchitectureModel(name, files.size, layout, layers, levels.orEmpty(), unitEdges, sliceEdges, rules, notes, applicability)
    }

    private fun sharedKernels(deps: List<Dep>, layout: Layout): List<String> {
        if (layout.mode != "SLICED" || layout.slices.size < 3) return emptyList()
        val cross = deps.filter { it.isCrossSlice() }
        val outgoing = cross.map { it.from.slice!! }.toSet()
        val incoming = cross.groupBy({ it.to!!.slice!! }, { it.from.slice!! }).mapValues { it.value.toSet().size }
        val needed = maxOf(2, (layout.slices.size - 1 + 1) / 2)
        return layout.slices.filter { it !in outgoing && (incoming[it] ?: 0) >= needed }.sorted()
    }

    private fun edgeStats(deps: List<Dep>, key: (Dep) -> Pair<String, String>): List<EdgeStat> =
        deps.groupBy(key).map { (k, ds) ->
            EdgeStat(k.first, k.second, ds.map { it.file.path }.distinct().size, ds.take(3).map { it.where })
        }.sortedWith(compareBy({ it.from }, { it.to }))

    private fun cycleRules(unitPairs: Set<Pair<String, String>>, sliceEdges: List<EdgeStat>, layout: Layout, notes: MutableList<String>): List<ArchRule> {
        val out = mutableListOf<ArchRule>()
        if (unitPairs.isNotEmpty()) {
            if (Graphs.cycles(unitPairs).isEmpty()) {
                out += ArchRule("no-cycles:unit", "no-cycles", "단위(레이어·패키지 묶음) 간 순환 의존 금지",
                    mapOf("scope" to listOf("unit")), "BLOCK", Evidence(unitPairs.size, unitPairs.size))
            } else notes += "단위 간 순환 의존이 이미 있어 순환 금지 규칙을 만들지 않았습니다: ${Graphs.cycles(unitPairs)}"
        }
        val slicePairs = sliceEdges.map { it.from to it.to }.toSet()
        if (layout.mode == "SLICED" && slicePairs.isNotEmpty()) {
            if (Graphs.cycles(slicePairs).isEmpty()) {
                out += ArchRule("no-cycles:slice", "no-cycles", "슬라이스 간 순환 의존 금지",
                    mapOf("scope" to listOf("slice")), "BLOCK", Evidence(slicePairs.size, slicePairs.size))
            } else notes += "슬라이스 간 순환 의존이 이미 있어 순환 금지 규칙을 만들지 않았습니다: ${Graphs.cycles(slicePairs)}"
        }
        return out
    }

    private fun leafRules(unitFiles: Map<String, List<SourceFile>>, leaves: Set<String>): List<ArchRule> =
        leaves.sorted().map { u ->
            val n = unitFiles.getValue(u).size
            ArchRule("leaf-unit:$u", "leaf-unit",
                "`$u` 은 다른 단위·슬라이스에 의존하지 않는다 (관측: ${n}개 파일 모두 해당)",
                mapOf("unit" to listOf(u)), if (n >= 5) "BLOCK" else "REVIEW", Evidence(n, n))
        }

    private fun absentRules(unitFiles: Map<String, List<SourceFile>>, pairs: Set<Pair<String, String>>, leaves: Set<String>, notes: MutableList<String>): List<ArchRule> {
        val used = pairs.map { it.second }.toSet()
        val rules = unitFiles.filter { it.key !in leaves }.keys.sorted().flatMap { a ->
            unitFiles.keys.sorted().filter { b -> b != a && b in used && (a to b) !in pairs && (b to a) !in pairs }.map { b ->
                ArchRule("absent-edge:$a>$b", "absent-edge",
                    "`$a` → `$b` 의존은 현재 없음 (양방향 0건). 의도된 분리인지 검토",
                    mapOf("from" to listOf(a), "to" to listOf(b)), "REVIEW", Evidence(unitFiles.getValue(a).size, unitFiles.getValue(a).size))
            }
        }
        if (rules.size > 12) {
            notes += "연결되지 않은 단위 쌍이 ${rules.size}개로 많아 absent-edge 후보를 생략했습니다"
            return emptyList()
        }
        return rules
    }

    private fun directionRules(edges: List<EdgeStat>, pairs: Set<Pair<String, String>>, leaves: Set<String>): List<ArchRule> =
        edges.filter { (it.to to it.from) !in pairs && it.to !in leaves }.map { e ->
            ArchRule(
                id = "forbid-edge:${e.to}>${e.from}",
                kind = "forbid-edge",
                description = "`${e.to}` → `${e.from}` 의존 금지 (관측: `${e.from}` → `${e.to}` ${e.files}개 파일, 반대 방향 0건)",
                params = mapOf("from" to listOf(e.to), "to" to listOf(e.from)),
                proposedSeverity = if (e.files >= 3) "BLOCK" else "REVIEW",
                evidence = Evidence(e.files, e.files, e.examples),
            )
        }

    private fun externalRules(unitFiles: Map<String, List<SourceFile>>, externalsByUnit: Map<String, List<String>>, deps: List<Dep>): List<ArchRule> =
        unitFiles.toSortedMap().mapNotNull { (unit, files) ->
            val externals = externalsByUnit[unit].orEmpty()
            when {
                externals.isEmpty() -> ArchRule("pure-layer:$unit", "pure-layer",
                    "`$unit` 은 외부 라이브러리(표준 라이브러리 제외)를 import 하지 않는다 (관측: ${files.size}개 파일 모두 해당)",
                    mapOf("layer" to listOf(unit)), if (files.size >= 5) "BLOCK" else "REVIEW", Evidence(files.size, files.size))
                externals.size <= 8 -> ArchRule("layer-externals:$unit", "layer-externals",
                    "`$unit` 이 사용하는 외부 라이브러리는 ${externals.joinToString { "`$it`" }} 로 한정",
                    mapOf("layer" to listOf(unit), "allowed" to externals), "REVIEW",
                    Evidence(files.size, files.size, deps.filter { it.from.unit == unit && it.external != null }.take(3).map { it.where }))
                else -> null
            }
        }

    private fun confinementRules(deps: List<Dep>, unitCount: Int): List<ArchRule> =
        deps.filter { it.external != null && it.from.unit !in pseudoUnits }.groupBy { it.external!! }.toSortedMap().mapNotNull { (prefix, ds) ->
            val files = ds.map { it.file.path }.distinct()
            if (files.size < 2) return@mapNotNull null
            val groups = ds.map { it.from.rolePath.split('.').filter(String::isNotEmpty) }.filter { it.isNotEmpty() }.groupBy { it[0] }
            if (groups.isEmpty() || groups.size > maxOf(1, unitCount / 2)) return@mapNotNull null
            val allowed = groups.values.map { g ->
                g.reduce { a, b -> a.zip(b).takeWhile { (x, y) -> x == y }.map { it.first } }.joinToString(".")
            }.sorted()
            ArchRule("confinement:$prefix", "confinement",
                "`$prefix` 는 ${allowed.joinToString { "`$it`" }} 안에서만 사용한다 (관측: ${files.size}개 파일 모두 해당)",
                mapOf("prefix" to listOf(prefix), "confinedTo" to allowed),
                if (files.size >= 5) "BLOCK" else "REVIEW", Evidence(files.size, files.size, ds.take(3).map { it.where }))
        }

    private fun sharedRules(deps: List<Dep>, layout: Layout): List<ArchRule> {
        if (layout.mode != "SLICED") return emptyList()
        val toShared = deps.filter { it.to != null && it.from.slice != null && it.to.slice == null && it.to.unit.startsWith("shared:") }
        val fromShared = deps.filter { it.to != null && it.from.slice == null && it.from.unit.startsWith("shared:") && it.to.slice != null }
        if (toShared.isEmpty() || fromShared.isNotEmpty()) return emptyList()
        val files = toShared.map { it.file.path }.distinct().size
        return listOf(
            ArchRule("shared-independence", "shared-independence",
                "공유 단위(슬라이스에 속하지 않는 패키지)는 슬라이스에 의존하지 않는다 (관측: 슬라이스 → 공유 ${files}개 파일, 공유 → 슬라이스 0건)",
                emptyMap(), "BLOCK", Evidence(files, files, toShared.take(3).map { it.where })),
        )
    }

    private fun crossSliceRules(cross: List<Dep>): List<ArchRule> {
        if (cross.isEmpty()) return emptyList()
        val pairs = cross.map { "${it.from.unit}>${it.to!!.unit}" }.toSortedSet().toList()
        val files = cross.map { it.file.path }.distinct().size
        return listOf(
            ArchRule("cross-slice-targets", "cross-slice-targets",
                "슬라이스 간 의존은 관측된 경로만 허용: ${pairs.joinToString { "`$it`" }}",
                mapOf("allowed" to pairs), "REVIEW", Evidence(files, files, cross.take(3).map { it.where })),
        )
    }
}
