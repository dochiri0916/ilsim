package com.dochiri.ilsim.runner.profile

internal class ParsedBuild(
    val coords: List<Pair<String, String?>>,
    val plugins: Map<String, String?>,
    val projectDeps: List<String>,
    val unresolvedRefs: Int,
    val jvmToolchain: Int?,
    val jvmTarget: Int?,
)

internal object GradleParser {
    private val coordRegex = Regex(
        """["']([a-z][A-Za-z0-9_\-]*(?:\.[A-Za-z0-9_\-]+)+):([A-Za-z0-9_.\-]+)(?::([A-Za-z0-9_.\-${'$'}{}]+))?["']""",
    )
    private val pluginRegex = Regex(
        """(?:\bid\s*\(?\s*["']([^"']+)["']\s*\)?|\bkotlin\(\s*["']([^"']+)["']\s*\))(?:\s*version\s*["']([^"']+)["'])?""",
    )
    private val bareRegex = Regex(
        """(?m)^\s*`?(java|java-library|application|war|groovy|checkstyle|pmd|jacoco|maven-publish)`?\s*$""",
    )
    private val applyRegex = Regex("""apply\s*\(\s*plugin\s*=\s*["']([^"']+)["']|apply\s+plugin\s*:\s*["']([^"']+)["']""")
    private val projectRegex = Regex("""project\(\s*["']:?([^"']+)["']\s*\)""")
    private val catalogLib = Regex("""\blibs\.(?!plugins\.|versions\.|bundles\.)([A-Za-z0-9_.]+)""")
    private val catalogPlugin = Regex("""\blibs\.plugins\.([A-Za-z0-9_.]+)""")
    private val kotlinPluginNames = Regex("""^(jvm|android|multiplatform|js|plugin\..+)$""")

    fun parse(text: String, catalog: Catalog): ParsedBuild {
        val declared = LinkedHashMap<String, Pair<String?, Boolean>>()
        pluginRegex.findAll(text).forEach { m ->
            val kotlinName = m.groups[2]?.value
            if (kotlinName != null && !kotlinPluginNames.matches(kotlinName)) return@forEach
            val id = m.groups[1]?.value ?: "org.jetbrains.kotlin.$kotlinName"
            val line = text.substring(m.range.first).substringBefore('\n')
            declared[id] = m.groups[3]?.value to Regex("""apply\s+false""").containsMatchIn(line)
        }
        bareRegex.findAll(text).forEach { declared.putIfAbsent(it.groupValues[1], null to false) }

        var unresolved = 0
        catalogPlugin.findAll(text).forEach { m ->
            val hit = catalog.resolve(catalog.plugins, m.groupValues[1])
            if (hit == null) unresolved++ else {
                val line = text.substring(m.range.first).substringBefore('\n')
                declared[hit.first] = hit.second to Regex("""apply\s+false""").containsMatchIn(line)
            }
        }

        val applied = applyRegex.findAll(text).map { it.groups[1]?.value ?: it.groups[2]!!.value }.toSet()
        val plugins = LinkedHashMap<String, String?>()
        declared.forEach { (id, v) -> if (!v.second || id in applied) plugins[id] = v.first }
        applied.forEach { plugins.putIfAbsent(it, null) }

        val coords = coordRegex.findAll(text)
            .map { "${it.groupValues[1]}:${it.groupValues[2]}" to it.groups[3]?.value }.toMutableList()
        catalogLib.findAll(text).forEach { m ->
            val hit = catalog.resolve(catalog.libraries, m.groupValues[1])
            if (hit == null) unresolved++ else coords += hit
        }

        return ParsedBuild(
            coords = coords,
            plugins = plugins,
            projectDeps = projectRegex.findAll(text).map { it.groupValues[1].replace(':', '/') }.distinct().toList(),
            unresolvedRefs = unresolved,
            jvmToolchain = Regex("""jvmToolchain\(\s*(\d+)\s*\)""").find(text)?.groupValues?.get(1)?.toInt()
                ?: Regex("""JavaLanguageVersion\.of\(\s*(\d+)\s*\)""").find(text)?.groupValues?.get(1)?.toInt(),
            jvmTarget = Regex("""JVM_(\d+)""").find(text)?.groupValues?.get(1)?.toInt()
                ?: Regex("""JavaVersion\.VERSION_(\d+)""").find(text)?.groupValues?.get(1)?.toInt(),
        )
    }

    fun includes(settings: String): List<String> {
        val quoted = Regex("""["']([^"']+)["']""")
        val paren = Regex("""\binclude\s*\(([^)]*)\)""").findAll(settings).flatMap { quoted.findAll(it.groupValues[1]) }
        val command = Regex("""(?m)^\s*include\s+([^\n(]+)$""").findAll(settings).flatMap { quoted.findAll(it.groupValues[1]) }
        return (paren + command).map { it.groupValues[1].trimStart(':').replace(':', '/') }.distinct().toList()
    }

    fun rootName(settings: String): String? =
        Regex("""rootProject\.name\s*=\s*["']([^"']+)["']""").find(settings)?.groupValues?.get(1)
}
