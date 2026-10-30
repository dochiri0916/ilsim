package com.dochiri.ilsim.runner.testing

import java.nio.file.Files
import java.nio.file.Path

object PitestDefault {
    private const val PLUGIN = "1.19.0"
    private const val PITEST = "1.22.1"
    private const val JUNIT5 = "1.2.1"

    private val source = Regex("""(^|/)src/main/(java|kotlin)/.+\.(java|kt)$""")
    private val pkg = Regex("""^\s*package\s+([\w.]+)""")

    private val script = """
        initscript {
            repositories { gradlePluginPortal() }
            dependencies { classpath 'info.solidsoft.gradle.pitest:gradle-pitest-plugin:$PLUGIN' }
        }
        allprojects {
            pluginManager.withPlugin('java') {
                apply plugin: info.solidsoft.gradle.pitest.PitestPlugin
                pitest {
                    pitestVersion = '$PITEST'
                    junit5PluginVersion = '$JUNIT5'
                    targetClasses = (System.getenv('ILSIM_MUTATION_TARGETS') ?: '*').split(',').toList()
                    targetTests = ['*']
                    outputFormats = ['XML']
                    timestampedReports = false
                    failWhenNoMutations = false
                    threads = Runtime.runtime.availableProcessors()
                }
            }
        }
    """.trimIndent() + "\n"

    fun command(root: Path, home: Path, changedSources: Set<String>): String? {
        if (!Files.isRegularFile(root.resolve("gradlew"))) return null
        val sources = changedSources.filter { source.containsMatchIn(it) && Files.isRegularFile(root.resolve(it)) }.sorted()
        if (sources.isEmpty()) return null
        val targets = sources.map { path ->
            val name = path.substringAfterLast('/').substringBeforeLast('.')
            val declared = Files.readAllLines(root.resolve(path)).firstNotNullOfOrNull { pkg.find(it)?.groupValues?.get(1) }
            listOfNotNull(declared, name).joinToString(".") + "*"
        }.distinct()
        val tasks = sources.map { it.substringBefore("src/main/").trimEnd('/') }.distinct()
            .map { if (it.isEmpty()) "pitest" else ":" + it.replace('/', ':') + ":pitest" }
        val init = home.resolve("mutation").resolve("pitest.init.gradle")
        Files.createDirectories(init.parent)
        Files.writeString(init, script)
        return "ILSIM_MUTATION_TARGETS='${targets.joinToString(",")}' ./gradlew ${tasks.joinToString(" ")} --init-script '$init' -q"
    }
}
