package com.dochiri.ilsim.runner.testing

import java.nio.file.Files
import java.nio.file.Path

object PitestDefault {
    private const val PLUGIN = "1.19.0"
    private const val PITEST = "1.22.1"
    private const val JUNIT5 = "1.2.3"

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
                tasks.named('pitest') { outputs.upToDateWhen { false } }
                pitest {
                    pitestVersion = '$PITEST'
                    junit5PluginVersion = '$JUNIT5'
                    targetClasses = (System.getenv('ILSIM_MUTATION_TARGETS') ?: '*').split(',').toList()
                    targetTests = ['*']
                    outputFormats = ['XML']
                    timestampedReports = false
                    failWhenNoMutations = false
                    threads = Runtime.runtime.availableProcessors()
                    additionalMutableCodePaths = project.provider {
                        def runtime = project.configurations.findByName('runtimeClasspath')
                        runtime == null ? [] : runtime.allDependencies.withType(ProjectDependency).collect { d -> project.rootProject.project(d.path) }.findAll { it.extensions.findByName('sourceSets') != null }.collectMany { it.sourceSets.main.output.classesDirs.files as List }
                    }
                    addJUnitPlatformLauncher = project.provider {
                        !project.configurations.matching { it.name in ['testImplementation', 'testRuntimeOnly'] }.any { c -> c.allDependencies.any { it.group == 'org.junit.platform' && it.name == 'junit-platform-launcher' } }
                    }
                }
            }
        }
    """.trimIndent() + "\n"

    fun command(root: Path, home: Path, changedSources: Set<String>, tests: Set<String> = emptySet()): String? {
        if (!Files.isRegularFile(root.resolve("gradlew"))) return null
        val sources = changedSources.filter { source.containsMatchIn(it) && Files.isRegularFile(root.resolve(it)) }.sorted()
        if (sources.isEmpty()) return null
        val targets = sources.map { path ->
            val name = path.substringAfterLast('/').substringBeforeLast('.')
            val declared = Files.readAllLines(root.resolve(path)).firstNotNullOfOrNull { pkg.find(it)?.groupValues?.get(1) }
            listOfNotNull(declared, name).joinToString(".") + "*"
        }.distinct()
        val tasks = (sources.map { it.substringBefore("src/main/") } + tests.filter { "src/test/" in it }.map { it.substringBefore("src/test/") }).map { it.trimEnd('/') }.distinct().sorted()
            .map { if (it.isEmpty()) "pitest" else ":" + it.replace('/', ':') + ":pitest" }
        val init = home.resolve("mutation").resolve("pitest.init.gradle")
        Files.createDirectories(init.parent)
        Files.writeString(init, script)
        return "ILSIM_MUTATION_TARGETS='${targets.joinToString(",")}' ./gradlew ${tasks.joinToString(" ")} --init-script '$init' -q"
    }
}
