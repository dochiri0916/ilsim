package com.dochiri.ilsim.runner.architecture

internal class Dep(
    val file: SourceFile,
    val line: Int,
    val name: String,
    val from: Loc,
    val to: Loc?,
    val external: String?,
) {
    val where get() = "${file.path}:$line"
}

internal object Dependencies {
    private val rootLevelTlds = setOf("org", "com", "io", "net", "dev", "tools", "de", "me")

    fun resolve(files: List<SourceFile>, locator: Locator): List<Dep> {
        val pkgs = files.map { it.pkg }.toSet()
        val out = mutableListOf<Dep>()
        for (f in files) {
            val from = locator.locate(f.pkg)
            for (imp in f.imports) {
                val internal = longestPackage(imp.name, pkgs)
                when {
                    internal != null -> if (internal != f.pkg) out += Dep(f, imp.line, imp.name, from, locator.locate(internal), null)
                    isStdlib(imp.name) -> Unit
                    else -> out += Dep(f, imp.line, imp.name, from, null, externalPrefix(imp.name))
                }
            }
        }
        return out
    }

    private fun longestPackage(name: String, pkgs: Set<String>): String? {
        var candidate = name
        while (true) {
            if (candidate in pkgs) return candidate
            val cut = candidate.lastIndexOf('.')
            if (cut < 0) return null
            candidate = candidate.substring(0, cut)
        }
    }

    private fun isStdlib(name: String) = name.startsWith("kotlin.") || name.startsWith("java.") || name == "kotlin"

    fun externalPrefix(name: String): String {
        val segs = name.split('.')
        val want = if (segs[0] in rootLevelTlds) 3 else 2
        return segs.take(minOf(want, maxOf(1, segs.size - 1))).joinToString(".")
    }
}
