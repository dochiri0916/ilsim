package com.dochiri.ilsim.runner.convention

import com.dochiri.ilsim.runner.architecture.Violation
import com.dochiri.ilsim.runner.profile.ProjectProfile
import java.nio.file.Path

object ConventionChecker {

    fun check(root: Path, profile: ProjectProfile, model: ConventionModel, approvedOnly: Boolean = false, onlyFiles: Set<String>? = null): List<Violation> =
        check(FactScanner.scan(root, profile.modules), model, approvedOnly, onlyFiles)

    internal fun check(facts: Facts, model: ConventionModel, approvedOnly: Boolean = false, onlyFiles: Set<String>? = null): List<Violation> {
        val out = mutableListOf<Violation>()
        for (rule in model.rules.filter { !it.rejected && (!approvedOnly || it.approved) }) {
            val probe = Probes.byKind(rule.kind) ?: continue
            probe.samples(facts, rule.params).filter { !it.ok && (onlyFiles == null || it.file in onlyFiles) }
                .mapTo(out) { Violation(rule.id, rule.severity, it.file, it.line, it.message) }
        }
        return out.sortedWith(compareBy({ it.file }, { it.line }, { it.ruleId }))
    }
}
