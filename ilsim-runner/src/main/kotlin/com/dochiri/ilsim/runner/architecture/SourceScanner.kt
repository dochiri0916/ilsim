package com.dochiri.ilsim.runner.architecture

import com.dochiri.ilsim.runner.profile.ModuleInfo
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.readLines

internal class ImportRef(val name: String, val line: Int)

internal class SourceFile(val module: String, val path: String, val pkg: String, val imports: List<ImportRef>)

internal object SourceScanner {
    private val pkgRegex = Regex("""^\s*package\s+([\w.`]+)\s*;?\s*$""")
    private val importRegex = Regex("""^\s*import\s+(?:static\s+)?([\w.`]+?)(?:\.\*)?(?:\s+as\s+\w+)?\s*;?\s*$""")

    fun scan(root: Path, modules: List<ModuleInfo>): List<SourceFile> {
        val out = mutableListOf<SourceFile>()
        for (m in modules) {
            for (rel in m.sourceRoots.filter(::mainRoot)) {
                val dir = root.resolve(rel)
                if (!dir.isDirectory()) continue
                Files.walk(dir).use { s ->
                    s.filter { Files.isRegularFile(it) && it.extension in setOf("kt", "java") }.forEach { f ->
                        parse(root, m.name, f)?.let(out::add)
                    }
                }
            }
        }
        return out
    }

    fun parse(root: Path, module: String, file: Path): SourceFile? = parse(module, root.relativize(file).toString(), file.readLines())

    fun parse(module: String, path: String, lines: List<String>): SourceFile? {
        var pkg: String? = null
        val imports = mutableListOf<ImportRef>()
        lines.forEachIndexed { i, raw ->
            val line = raw.trim()
            if (pkg == null) pkgRegex.matchEntire(line)?.let { pkg = it.groupValues[1].replace("`", "") }
            else importRegex.matchEntire(line)?.let { imports += ImportRef(it.groupValues[1].replace("`", ""), i + 1) }
        }
        val p = pkg ?: return null
        return SourceFile(module, path, p, imports)
    }

    fun mainModule(modules: List<ModuleInfo>, path: String): String? =
        modules.firstOrNull { m -> m.sourceRoots.any { rel -> mainRoot(rel) && path.startsWith("$rel/") } }?.name

    private fun mainRoot(rel: String): Boolean = rel == "src/main/kotlin" || rel == "src/main/java" || rel.contains("/src/main/") || rel.startsWith("src/main/")
}
