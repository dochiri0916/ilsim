package com.dochiri.ilsim.runner.convention

import com.dochiri.ilsim.runner.architecture.Violation
import com.dochiri.ilsim.runner.profile.ProjectProfile
import java.nio.file.Path

object ConventionChecker {

    /**
     * [approvedOnly] 가 true 면 승인한 규칙만 적용한다(게이트용). 거절한 규칙은 항상 제외한다.
     * [onlyFiles] 가 있으면 그 파일의 위반만 보고한다: 기존 코드의 예외는 두고 새로 바꾼 코드에만 강제하기 위함이다.
     */
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
