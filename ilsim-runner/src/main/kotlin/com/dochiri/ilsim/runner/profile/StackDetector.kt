package com.dochiri.ilsim.runner.profile

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText

object StackDetector {

    private val ignoredDirs =
        setOf(".git", ".gradle", ".idea", ".vscode", "build", "target", "out", "node_modules", "__pycache__", ".venv")
    private val languageByExt = mapOf("kt" to "Kotlin", "java" to "Java", "groovy" to "Groovy")
    private const val MAX_READ_BYTES = 1_000_000L

    private data class Fact(
        val kind: Kind,
        val key: String,
        val version: String?,
        val module: String,
        val evidence: String
    ) {
        enum class Kind { COORD, PLUGIN }
    }

    fun detect(rootInput: Path): ProjectProfile {
        val root = rootInput.toAbsolutePath().normalize()
        require(root.isDirectory()) { "디렉터리가 아닙니다: $root" }
        val warnings = mutableListOf<String>()

        val settingsFile =
            listOf("settings.gradle.kts", "settings.gradle").map(root::resolve).firstOrNull { it.exists() }
        val rootBuild = listOf("build.gradle.kts", "build.gradle").map(root::resolve).firstOrNull { it.exists() }
        val buildTool = when {
            settingsFile != null || rootBuild != null ->
                if ((settingsFile ?: rootBuild)!!.name.endsWith(".kts")) "gradle-kotlin-dsl" else "gradle-groovy"

            root.resolve("pom.xml").exists() -> "maven"
            root.resolve("package.json").exists() -> "npm"
            else -> "unknown"
        }
        if (buildTool == "maven") warnings += "Maven은 빌드 도구만 식별하고 모듈·의존성은 해석하지 않습니다"
        if (buildTool == "unknown") warnings += "빌드 파일을 찾지 못했습니다"
        if (buildTool.startsWith("gradle")) {
            warnings += "Gradle 파일을 실행하지 않고 텍스트로 해석했습니다. 조건부·동적 선언은 반영되지 않을 수 있습니다"
        }

        val settingsText = settingsFile?.readText().orEmpty()
        val catalog = Catalog.load(root)
        val included = if (buildTool.startsWith("gradle")) GradleParser.includes(settingsText) else emptyList()
        if (Regex("""projectDir""").containsMatchIn(settingsText)) {
            warnings += "settings에서 projectDir를 재지정한 모듈은 경로를 잘못 해석할 수 있습니다"
        }

        val rootText = rootBuild?.readText().orEmpty()
        val rootParsed = GradleParser.parse(rootText, catalog)
        val sharedRoot = included.isNotEmpty() && Regex("""\b(subprojects|allprojects)\b""").containsMatchIn(rootText)
        val (sharedText, conditionals) = if (sharedRoot) NameConditions.split(rootText) else rootText to emptyList()
        val sharedBase = GradleParser.parse(sharedText, catalog)
        val sharedConditional = conditionals.map { it to GradleParser.parse(it.body, catalog) }
        if (sharedRoot && Regex("""\bif\s*\(""").containsMatchIn(sharedText)) {
            warnings += "루트 subprojects 블록에 해석하지 못한 조건문이 있어 공통 의존성이 일부 모듈에는 적용되지 않을 수 있습니다"
        }

        val moduleDirs = if (included.isEmpty()) listOf("." to root) else included.map { it to root.resolve(it) }
        val facts = mutableListOf<Fact>()
        val modules = mutableListOf<ModuleInfo>()
        var unresolved = rootParsed.unresolvedRefs
        var toolchain = rootParsed.jvmToolchain
        var target = rootParsed.jvmTarget
        val rootLabel = rootBuild?.let { root.relativize(it).toString() }

        fun addFacts(parsed: ParsedBuild, module: String, evidence: String) {
            parsed.coords.forEach { (k, v) -> facts += Fact(Fact.Kind.COORD, k, v, module, evidence) }
            parsed.plugins.forEach { (k, v) -> facts += Fact(Fact.Kind.PLUGIN, k, v, module, evidence) }
        }

        for ((name, dir) in moduleDirs) {
            if (!dir.isDirectory()) {
                warnings += "include 된 모듈 디렉터리가 없습니다: $name"
                continue
            }
            val build = if (name == ".") rootBuild
            else listOf("build.gradle.kts", "build.gradle").map(dir::resolve).firstOrNull { it.exists() }
            val parsed = if (name == ".") rootParsed else GradleParser.parse(build?.readText().orEmpty(), catalog)
            if (name != ".") {
                unresolved += parsed.unresolvedRefs
                toolchain = toolchain ?: parsed.jvmToolchain
                target = target ?: parsed.jvmTarget
            }
            val label = build?.let { root.relativize(it).toString() }
            if (label != null) addFacts(parsed, name, label)
            if (sharedRoot && name != "." && rootLabel != null) {
                addFacts(sharedBase, name, "$rootLabel (subprojects 공통)")
                val simple = name.substringAfterLast('/')
                sharedConditional.filter { (cond, _) -> cond.matches(simple) }
                    .forEach { (_, p) -> addFacts(p, name, "$rootLabel (subprojects 조건부)") }
            }
            val dynamic = parsed.projectDeps.any { '$' in it }
            if (dynamic) warnings += "$name: 동적 project() 의존성은 해석하지 못했습니다"
            modules += scanModule(root, name, dir, label, parsed.projectDeps.filterNot { '$' in it })
        }
        if (included.isEmpty() && rootLabel == null) modules.clear()
            .also { modules += scanModule(root, ".", root, null, emptyList()) }
        if (unresolved > 0) warnings += "버전 카탈로그 참조 ${unresolved}건을 해석하지 못했습니다"

        val languages =
            modules.flatMap { it.mainFiles.entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.sum() }
        val primary = languages.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key

        val configFiles = mutableListOf<String>()
        val infrastructure = mutableListOf<String>()
        classifyFiles(root, configFiles, infrastructure)

        val archTests = modules.flatMap { m ->
            val dir = if (m.path == ".") root else root.resolve(m.path)
            val testRoots = dir.resolve("src").takeIf { it.isDirectory() }
                ?.let { src ->
                    Files.list(src).use { s -> s.filter { it.isDirectory() && it.name != "main" }.toList() }
                }.orEmpty()
            testRoots.flatMap { walkFiles(it, ignore = setOf("node_modules", ".git")) }
                .filter { it.name.endsWith(".kt") || it.name.endsWith(".java") }
                .filter { readSmall(it)?.contains("com.tngtech.archunit") == true }
                .map { root.relativize(it).toString() }
        }.sorted()

        fun aggregate(category: Category): List<Detected> =
            RULES.filter { it.category == category }.mapNotNull { rule ->
                val hit = facts.filter { f ->
                    when (f.kind) {
                        Fact.Kind.COORD -> rule.coords.any { it.matches(f.key) }
                        Fact.Kind.PLUGIN -> rule.plugins.any { it.matches(f.key) }
                    }
                }
                if (hit.isEmpty()) null
                else Detected(
                    id = rule.id,
                    label = rule.label,
                    version = hit.firstNotNullOfOrNull { it.version },
                    modules = hit.map { it.module }.distinct().sorted(),
                    evidence = hit.map { it.evidence }.distinct().take(5),
                )
            }

        val pluginVersion =
            { id: String -> facts.firstOrNull { it.kind == Fact.Kind.PLUGIN && it.key == id && it.version != null }?.version }
        val wrapper = root.resolve("gradle/wrapper/gradle-wrapper.properties").takeIf { it.exists() }
            ?.let { Regex("""gradle-([0-9][0-9.]*)-(?:bin|all)\.zip""").find(it.readText())?.groupValues?.get(1) }

        return ProjectProfile(
            root = root.toString(),
            name = GradleParser.rootName(settingsText) ?: root.name,
            vcs = if (root.resolve(".git").exists()) "git" else null,
            buildTool = buildTool,
            primaryLanguage = primary,
            languages = languages,
            jvm = JvmInfo(
                kotlin = pluginVersion("org.jetbrains.kotlin.jvm"),
                javaToolchain = toolchain,
                jvmTarget = target,
                springBoot = pluginVersion("org.springframework.boot"),
                gradleWrapper = wrapper,
            ),
            modules = modules,
            frameworks = aggregate(Category.FRAMEWORK),
            testing = aggregate(Category.TESTING),
            analysisTools = aggregate(Category.ANALYSIS),
            configFiles = configFiles.sorted(),
            infrastructure = infrastructure.sorted(),
            architectureTests = archTests,
            warnings = warnings,
        )
    }

    private fun scanModule(root: Path, name: String, dir: Path, buildLabel: String?, deps: List<String>): ModuleInfo {
        val src = dir.resolve("src")
        val main = linkedMapOf<String, Int>()
        var testFiles = 0
        var bootApp = false
        val sourceRoots = mutableListOf<String>()
        if (src.isDirectory()) {
            Files.list(src).use { s -> s.filter { it.isDirectory() }.toList() }.sortedBy { it.name }.forEach { set ->
                listOf("kotlin", "java", "groovy").map(set::resolve).filter { it.isDirectory() }
                    .forEach { sourceRoots += root.relativize(it).toString() }
                walkFiles(set, ignore = setOf("node_modules", ".git")).forEach { f ->
                    val lang = languageByExt[f.name.substringAfterLast('.', "")] ?: return@forEach
                    if (set.name == "main") {
                        main.merge(lang, 1, Int::plus)
                        if (!bootApp && f.name.matches(Regex(""".*Application\.(kt|java)"""))) {
                            bootApp = readSmall(f)?.contains("@SpringBootApplication") == true
                        }
                    } else testFiles++
                }
            }
        }
        return ModuleInfo(
            name = name,
            path = name,
            buildFile = buildLabel,
            mainFiles = main,
            testFiles = testFiles,
            sourceRoots = sourceRoots,
            springBootApplication = bootApp,
            projectDependencies = deps,
        )
    }

    private fun classifyFiles(root: Path, config: MutableList<String>, infra: MutableList<String>) {
        val configNames = listOf(
            Regex("""\.editorconfig"""),
            Regex("""detekt.*\.ya?ml"""),
            Regex("""\.ktlint.*"""),
            Regex("""checkstyle.*\.xml"""),
            Regex("""(pmd|ruleset).*\.xml"""),
            Regex("""spotbugs.*\.xml"""),
            Regex("""\.code-risk\.yml"""),
            Regex("""sonar-project\.properties"""),
            Regex("""\.pre-commit-config\.ya?ml"""),
            Regex("""lefthook\.ya?ml"""),
        )
        val infraNames = listOf(
            Regex("""Dockerfile.*"""), Regex("""(docker-)?compose.*\.ya?ml"""), Regex("""Jenkinsfile"""),
            Regex("""\.gitlab-ci\.ya?ml"""), Regex("""Chart\.yaml"""),
        )
        walkFiles(root, maxDepth = 5).forEach { f ->
            val rel = root.relativize(f).toString()
            when {
                rel.startsWith(".github/workflows/") -> infra += rel
                configNames.any { it.matches(f.name) } -> config += rel
                infraNames.any { it.matches(f.name) } -> infra += rel
                f.parent?.endsWith("db/migration") == true && rel.contains("src/main/resources") ->
                    "${rel.substringBeforeLast('/')} (DB 마이그레이션)".let { if (it !in infra) infra += it }
            }
        }
        Files.list(root)
            .use { s -> s.filter { it.isDirectory() && it.name in setOf("k8s", "helm", "nginx", "deploy") }.toList() }
            .forEach { infra += root.relativize(it).toString() + "/" }
    }

    private fun readSmall(file: Path): String? = try {
        if (Files.size(file) <= MAX_READ_BYTES) file.readText() else null
    } catch (_: IOException) {
        null
    }

    private fun walkFiles(start: Path, maxDepth: Int = Int.MAX_VALUE, ignore: Set<String> = ignoredDirs): List<Path> {
        val out = mutableListOf<Path>()
        Files.walkFileTree(start, emptySet(), maxDepth, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                if (dir != start && dir.name in ignore) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile) out.add(file)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        })
        return out
    }
}
