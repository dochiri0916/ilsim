package com.dochiri.ilsim.runner.architecture

import kotlinx.serialization.Serializable

/**
 * 이 프로젝트에 아키텍처 검증을 적용할 수 있는지에 대한 판정. 구조에서 계산한 수치로만 정하며 AI를 쓰지 않는다.
 * status: APPLICABLE(전체 적용) | PARTIAL(일관된 영역만 적용, 나머지는 미검증) | NOT_APPLICABLE(검증하지 않음, 게이트는 SKIPPED).
 */
@Serializable
data class Applicability(
    val status: String,
    val metrics: Map<String, Double> = emptyMap(),
    val reasons: List<String> = emptyList(),
    /** PARTIAL 일 때 검증에서 제외하는 단위·슬라이스. */
    val unverifiedUnits: List<String> = emptyList(),
    val unverifiedSlices: List<String> = emptyList(),
) {
    companion object {
        const val APPLICABLE = "APPLICABLE"
        const val PARTIAL = "PARTIAL"
        const val NOT_APPLICABLE = "NOT_APPLICABLE"
    }
}

internal object ApplicabilityAssessor {
    /** 단위로 분류된 파일 비율: 이 미만이면 비적용, 미만(PARTIAL)이면 부분 적용. */
    const val MIN_CLASSIFIED = 0.5
    const val FULL_CLASSIFIED = 0.8
    /** 순환에 속한 단위 비율이 이 이상이면 층 구조가 없는 것으로 본다. */
    const val MAX_CYCLIC_UNITS = 0.5
    /** 슬라이스가 갖춘 레이어 비율(평균)이 이 미만이면 슬라이스들이 같은 구조가 아닌 것으로 본다. */
    const val MIN_UNIFORMITY = 0.4
    /** 개별 슬라이스가 이 미만이면 해당 슬라이스만 제외한다. */
    const val SLICE_UNIFORMITY = 0.5

    fun notApplicable(reason: String) = Applicability(Applicability.NOT_APPLICABLE, reasons = listOf(reason))

    fun assess(
        files: List<SourceFile>,
        layout: Layout,
        locator: Locator,
        units: List<String>,
        unitPairs: Set<Pair<String, String>>,
        slicePairs: Set<Pair<String, String>>,
        ruleCount: Int,
    ): Applicability {
        val reasons = mutableListOf<String>()
        val metrics = linkedMapOf<String, Double>()
        var hard = false
        var soft = false

        val locs = files.map { locator.locate(it.pkg) }
        val classified = if (files.isEmpty()) 0.0 else locs.count { it.unit !in pseudoUnits }.toDouble() / files.size
        metrics["classifiedFiles"] = classified
        if (classified < MIN_CLASSIFIED) { hard = true; reasons += "단위로 분류되는 파일이 ${pct(classified)}뿐입니다" }
        else if (classified < FULL_CLASSIFIED) { soft = true; reasons += "단위로 분류되지 않는 파일이 ${pct(1 - classified)} 있습니다 (검증 대상 아님)" }

        val unitCycles = Graphs.cycles(unitPairs)
        val cyclicUnits = unitCycles.flatten().filter { it in units }.toSortedSet()
        val cyclicRatio = if (units.isEmpty()) 0.0 else cyclicUnits.size.toDouble() / units.size
        metrics["cyclicUnits"] = cyclicRatio
        if (cyclicRatio >= MAX_CYCLIC_UNITS) { hard = true; reasons += "단위의 ${pct(cyclicRatio)}가 순환 의존에 속해 층 구조가 없습니다" }
        else if (cyclicUnits.isNotEmpty()) { soft = true; reasons += "순환 의존에 속한 단위는 검증에서 제외합니다: ${cyclicUnits.joinToString()}" }

        val sliceCycles = Graphs.cycles(slicePairs)
        if (sliceCycles.isNotEmpty()) {
            soft = true
            reasons += "슬라이스 간 순환 의존이 있어 슬라이스 순환 금지 규칙은 적용하지 않습니다: ${sliceCycles.joinToString { it.sorted().joinToString("↔") }}"
        }
        metrics["cyclicSlices"] = if (layout.slices.isEmpty()) 0.0 else sliceCycles.flatten().size.toDouble() / layout.slices.size

        val unverifiedSlices = mutableListOf<String>()
        if (layout.mode == "SLICED" && layout.slices.isNotEmpty() && layout.layerNames.isNotEmpty()) {
            val perSlice = layout.slices.associateWith { s ->
                val have = locs.filter { it.slice == s && it.unit in layout.layerNames }.map { it.unit }.toSet()
                have.size.toDouble() / layout.layerNames.size
            }
            val avg = perSlice.values.average()
            metrics["sliceUniformity"] = avg
            if (avg < MIN_UNIFORMITY) { hard = true; reasons += "슬라이스들이 같은 레이어 구조를 갖지 않습니다 (평균 ${pct(avg)})" }
            else perSlice.filterValues { it < SLICE_UNIFORMITY }.keys.sorted().forEach {
                soft = true; unverifiedSlices += it
                reasons += "슬라이스 `$it` 는 다른 슬라이스와 구조가 달라 검증에서 제외합니다 (${pct(perSlice.getValue(it))})"
            }
        }
        if (layout.mode == "FLAT" && units.size < 2) { hard = true; reasons += "독립된 단위가 하나뿐입니다" }
        if (ruleCount == 0) { hard = true; reasons += "관측에서 만들 수 있는 규칙이 없습니다" }

        val status = when {
            hard -> Applicability.NOT_APPLICABLE
            soft -> Applicability.PARTIAL
            else -> Applicability.APPLICABLE
        }
        return Applicability(
            status, metrics, reasons,
            unverifiedUnits = if (status == Applicability.PARTIAL) cyclicUnits.toList() else emptyList(),
            unverifiedSlices = if (status == Applicability.PARTIAL) unverifiedSlices else emptyList(),
        )
    }

    private fun pct(v: Double) = "${Math.round(v * 100)}%"
}
