package com.dochiri.ilsim.runner.convention

import com.dochiri.ilsim.runner.AssignedTask
import com.dochiri.ilsim.runner.ConfigStore
import com.dochiri.ilsim.runner.ProjectEntry
import com.dochiri.ilsim.runner.RunnerConfig
import com.dochiri.ilsim.runner.Shell
import com.dochiri.ilsim.runner.StageGates
import com.dochiri.ilsim.runner.architecture.ArchitectureStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class DocEnforcementCorpusTest {
    @Serializable
    private data class Rule(val id: String, val kind: String, val description: String, val line: Int, val params: Map<String, List<String>>)

    @Serializable
    private data class Case(val stage: String, val expect: String, val note: String, val doc: String, val rules: List<Rule>)

    private val corpus = Path.of("corpus/doc-enforcement")
    private val json = Json { ignoreUnknownKeys = true }

    private fun copy(from: Path, to: Path) {
        if (!from.exists()) return
        Files.walk(from).use { s ->
            s.filter { it.isRegularFile() }.forEach { f ->
                val target = to.resolve(f.relativeTo(from).toString().removeSuffix(".txt"))
                target.parent.createDirectories()
                f.copyTo(target, overwrite = true)
            }
        }
    }

    private fun run(dir: Path): String? {
        val case = json.decodeFromString<Case>(dir.resolve("case.json").readText())
        val home = Files.createTempDirectory("ilsim-doc-corpus-home")
        val repo = Files.createTempDirectory("ilsim-doc-corpus-repo").toRealPath()
        try {
            Shell.gitOk(repo, "init", "-q", "-b", "main")
            repo.resolve("settings.gradle.kts").writeText("rootProject.name = \"demo\"\n")
            repo.resolve("build.gradle.kts").writeText("plugins { kotlin(\"jvm\") }\n")
            copy(dir.resolve("before"), repo)
            Shell.gitOk(repo, "add", "-A")
            Shell.gitOk(repo, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "base")
            copy(dir.resolve("after"), repo)

            val store = ConfigStore(home.resolve(".ilsim/runner.json"))
            store.save(RunnerConfig(runnerId = "r1", projects = mapOf("p" to ProjectEntry(repo.toString(), "demo"))))
            val rules = case.rules.map { r ->
                ConventionRule(r.id, r.kind, r.description, DocProbes.normalize(r.kind, r.params), approved = true, origin = DocOrigin(case.doc, r.line, "", r.description, r.id))
            }
            DocRuleStore(store).save("p", DocRules.DocRuleModel(rules))
            val validation = linkedMapOf<String, String>()
            StageGates(store, ArchitectureStore(store), ConventionStore(store), { _, _ -> "" }, {}).quickCheck(AssignedTask("t", "p", case.stage, 1, "f", "m", prompt = ""), repo, validation)
            val blocked = validation["doc-rules"] == "FAILED"
            val ok = if (case.expect == "BLOCK") blocked else !blocked
            return if (ok) null else "${dir.name}: 기대 ${case.expect}, 실제 doc-rules=${validation["doc-rules"]} (${case.note})"
        } finally {
            home.toFile().deleteRecursively()
            repo.toFile().deleteRecursively()
        }
    }

    @Test
    fun `문서 규칙 회귀 세트의 모든 사례를 기대대로 막거나 통과시킨다`() {
        val cases = corpus.listDirectoryEntries().filter { it.isDirectory() && it.name != "open" && it.resolve("case.json").exists() }.sortedBy { it.name }
        assertTrue(cases.size >= 8, "회귀 세트가 비어 있습니다")
        assertEquals(emptyList(), cases.mapNotNull(::run))
    }
}
