package com.dochiri.ilsim.runner.architecture

object CrossValidation {
    private const val MIN_UNITS = 3

    data class Result(val unstable: Map<String, List<String>>)

    internal fun check(files: List<SourceFile>, full: ArchitectureModel): Result {
        if (full.layout.mode == "UNKNOWN") return Result(emptyMap())
        val locator = Locator(full.layout)
        val bySlice = full.layout.mode == "SLICED" && full.layout.slices.size >= MIN_UNITS
        val byUnit = files.groupBy { locator.locate(it.pkg).let { loc -> if (bySlice) loc.slice ?: "" else loc.unit } }
            .filterKeys { it.isNotEmpty() && it !in pseudoUnits && !it.startsWith("shared:") }
        if (byUnit.size < MIN_UNITS) return Result(emptyMap())
        val ids = full.rules.map { it.id }.toSet()
        val unstable = linkedMapOf<String, MutableList<String>>()
        for ((unit, held) in byUnit) {
            val heldPaths = held.map { it.path }.toSet()
            val trained = ArchitectureMiner.mine(files.filterNot { it.path in heldPaths }, full.projectName)
            val rules = trained.rules.filter { it.id in ids }
            if (rules.isEmpty()) continue
            ArchitectureChecker.check(files, trained.copy(layout = full.layout, rules = rules))
                .filter { it.file in heldPaths }
                .map { it.ruleId }.distinct()
                .forEach { unstable.getOrPut(it) { mutableListOf() } += unit }
        }
        return Result(unstable)
    }
}
