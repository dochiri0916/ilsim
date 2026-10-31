package com.dochiri.ilsim.runner.architecture

object ArchitectureSelfTest {
    private const val SYNTHETIC = "ilsim-selftest/"
    private const val PROBE = "IlsimProbe"

    data class Result(val caught: List<String>, val missed: List<String>) {
        val total: Int get() = caught.size + missed.size
    }

    internal fun run(files: List<SourceFile>, model: ArchitectureModel): Result {
        val rules = model.rules.filter { !it.rejected }
        if (rules.isEmpty() || files.isEmpty()) return Result(emptyList(), emptyList())
        val locator = Locator(model.layout)
        val reps = files.groupBy { locator.locate(it.pkg) }.filterKeys { it.unit !in pseudoUnits }.mapValues { it.value.first() }.values.toList()
        val targets = reps.map { "${it.pkg}.${it.path.substringAfterLast('/').substringBeforeLast('.')}" }
        val externals = (rules.filter { it.kind == "confinement" }.mapNotNull { it.params["prefix"]?.firstOrNull() } + "org.ilsimprobe.selftest").distinct().map { "$it.$PROBE" }
        var n = 0
        val synthetic = reps.flatMap { from ->
            (targets + externals).filterNot { it.startsWith("${from.pkg}.") && it.count { c -> c == '.' } == from.pkg.count { c -> c == '.' } + 1 }
                .map { SourceFile(from.module, "$SYNTHETIC${n++}/${from.path.substringAfterLast('/')}", from.pkg, listOf(ImportRef(it, 1))) }
        }
        val hit = ArchitectureChecker.check(files + synthetic, model.copy(rules = rules))
            .filter { it.file.startsWith(SYNTHETIC) }.map { it.ruleId }.toSet()
        return Result(rules.map { it.id }.filter { it in hit }, rules.map { it.id }.filterNot { it in hit })
    }
}
