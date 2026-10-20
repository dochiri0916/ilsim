package com.dochiri.ilsim.runner.convention

import com.dochiri.ilsim.runner.architecture.Evidence
import com.dochiri.ilsim.runner.profile.ProjectProfile
import java.nio.file.Path

object ConventionMiner {
    const val MIN_RATIO = 0.9
    const val BLOCK_RATIO = 0.98
    const val MIN_BLOCK_SAMPLES = 5

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
                val severity = if (ratio >= BLOCK_RATIO && c.maxSeverity == "BLOCK" && samples.size >= MIN_BLOCK_SAMPLES) "BLOCK" else "REVIEW"
                val id = if (c.key == "main" || c.key == "test") probe.kind else "${probe.kind}:${c.key}"
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
        return ConventionModel(name, facts.main.size, facts.tests.size, langs, merge(rules.sortedBy { it.id }, signatures), notes, inventory = inventory(facts))
    }

    private fun inventory(facts: Facts): List<String> =
        facts.main.groupBy { it.pkg }.toSortedMap().map { (pkg, files) ->
            val names = files.flatMap { f -> f.types.filter { it.topLevel }.map { it.name } }.sorted()
            val anns = files.flatMap { f -> f.annotated.flatMap { it.annotations } }.groupingBy { it }.eachCount().entries
                .sortedByDescending { it.value }.take(8).joinToString(",") { "@${it.key}×${it.value}" }
            "$pkg: ${names.size}개 [${names.take(8).joinToString(",")}] $anns"
        }

    private fun merge(rules: List<ConventionRule>, signatures: Map<String, String>): List<ConventionRule> =
        rules.groupBy { signatures.getValue(it.id) }.values.map { group ->
            val ordered = group.sortedBy { if (it.params["annotation"]?.firstOrNull() in Probes.roleAnnotations) 0 else 1 }
            val head = ordered.first()
            val same = ordered.drop(1).mapNotNull { it.params["annotation"]?.firstOrNull() }
            if (same.isEmpty()) head
            else head.copy(
                description = head.description + " · 같은 대상의 어노테이션: ${same.joinToString { "@$it" }}",
                params = head.params + ("annotation" to (head.params.getValue("annotation") + same)),
            )
        }.sortedBy { it.id }

    private fun pct(v: Double) = "${Math.round(v * 100)}%"
}
