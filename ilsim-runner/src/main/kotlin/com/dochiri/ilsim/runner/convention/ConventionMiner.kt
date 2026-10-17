package com.dochiri.ilsim.runner.convention

import com.dochiri.ilsim.runner.architecture.Evidence
import com.dochiri.ilsim.runner.profile.ProjectProfile
import java.nio.file.Path

/**
 * 소스에서 코드 컨벤션을 추론한다. 정해진 컨벤션 목록이 아니라, 측정 가능한 항목(이름 접미사, 들여쓰기, 줄 길이 등)마다
 * 이 프로젝트에서 가장 많이 쓰인 방식을 찾고 준수 비율이 충분히 높을 때만 규칙 후보로 만든다.
 */
object ConventionMiner {
    /** 준수 비율이 이 이상이면 후보, [BLOCK_RATIO] 이상이면 BLOCK 후보. */
    const val MIN_RATIO = 0.9
    const val BLOCK_RATIO = 0.98

    fun mine(root: Path, profile: ProjectProfile): ConventionModel = mine(FactScanner.scan(root, profile.modules), profile.name)

    internal fun mine(facts: Facts, name: String?): ConventionModel {
        val rules = mutableListOf<ConventionRule>()
        val signatures = mutableMapOf<String, String>()
        val notes = mutableListOf<String>()
        for (probe in Probes.all) {
            for (c in probe.candidates(facts)) {
                val samples = probe.samples(facts, c.params)
                if (samples.size < probe.minSamples) continue
                val ok = samples.count { it.ok }
                val ratio = ok.toDouble() / samples.size
                if (ratio < MIN_RATIO) {
                    notes += "일관된 관행이 없어 규칙으로 만들지 않았습니다: ${c.description} (${pct(ratio)}, $ok/${samples.size})"
                    continue
                }
                val severity = if (ratio >= BLOCK_RATIO && c.maxSeverity == "BLOCK") "BLOCK" else "REVIEW"
                val id = if (c.key == "main" || c.key == "test") probe.kind else "${probe.kind}:${c.key}"
                // 같은 클래스·선언에 여러 어노테이션이 붙어 관측 대상이 똑같은 규칙은 하나로 합친다.
                signatures[id] = probe.kind + samples.joinToString(",") { "${it.file}:${it.line}:${it.ok}" }
                rules += ConventionRule(
                    id = id,
                    kind = probe.kind,
                    description = "${c.description} (관측: $ok/${samples.size})",
                    params = c.params,
                    severity = severity,
                    evidence = Evidence(ok, samples.size, samples.filter { !it.ok }.take(3).map { "${it.file}:${it.line}" }),
                )
            }
        }
        if (facts.main.isEmpty()) notes += "분석할 main 소스가 없습니다"
        val langs = facts.files.groupingBy { it.lang }.eachCount().toSortedMap()
        return ConventionModel(name, facts.main.size, facts.tests.size, langs, merge(rules.sortedBy { it.id }, signatures), notes)
    }

    private fun merge(rules: List<ConventionRule>, signatures: Map<String, String>): List<ConventionRule> =
        rules.groupBy { signatures.getValue(it.id) }.values.map { group ->
            val head = group.first()
            val same = group.drop(1).mapNotNull { it.params["annotation"]?.firstOrNull() }
            if (same.isEmpty()) head
            else head.copy(
                description = head.description + " · 같은 대상의 어노테이션: ${same.joinToString { "@$it" }}",
                params = head.params + ("annotation" to (head.params.getValue("annotation") + same)),
            )
        }.sortedBy { it.id }

    private fun pct(v: Double) = "${Math.round(v * 100)}%"
}
