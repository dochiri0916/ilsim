package com.dochiri.ilsim.runner

import com.dochiri.ilsim.runner.architecture.ArchRule
import com.dochiri.ilsim.runner.architecture.ArchitectureModel
import com.dochiri.ilsim.runner.architecture.ArchitectureStore
import com.dochiri.ilsim.runner.architecture.Evidence
import com.dochiri.ilsim.runner.architecture.Layout
import com.dochiri.ilsim.runner.convention.ConventionModel
import com.dochiri.ilsim.runner.convention.ConventionRule
import com.dochiri.ilsim.runner.convention.ConventionStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FreezeGateTest {
    private val task = AssignedTask("t", "p", "IMPLEMENTATION", 1, "f", "m", prompt = "")

    private fun repo(files: Map<String, String>): Path {
        val repo = Files.createTempDirectory("ilsim-freeze-repo").toRealPath()
        Shell.gitOk(repo, "init", "-q", "-b", "main")
        repo.resolve("settings.gradle.kts").writeText("rootProject.name = \"demo\"\n")
        repo.resolve("build.gradle.kts").writeText("plugins { kotlin(\"jvm\") }\n")
        files.forEach { (path, text) -> repo.resolve(path).also { it.parent.createDirectories() }.writeText(text) }
        Shell.gitOk(repo, "add", "-A")
        Shell.gitOk(repo, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "base")
        return repo
    }

    private fun store(repo: Path): ConfigStore {
        val home = Files.createTempDirectory("ilsim-freeze-home")
        return ConfigStore(home.resolve(".ilsim/runner.json")).also { it.save(RunnerConfig(runnerId = "r1", projects = mapOf("p" to ProjectEntry(repo.toString(), "demo")))) }
    }

    private val sources = mapOf(
        "src/main/kotlin/com/x/domain/D.kt" to "package com.x.domain\n\nimport com.x.service.S\n\nclass D\n",
        "src/main/kotlin/com/x/service/S.kt" to "package com.x.service\n\nclass S\n",
        "src/main/kotlin/com/x/service/T.kt" to "package com.x.service\n\nclass T\n",
    )

    private fun architecture(store: ConfigStore, severity: String) = ArchitectureStore(store).save("p", ArchitectureModel(
        "demo", 3, Layout("com.x", "FLAT"), emptyList(), emptyList(), emptyList(), emptyList(),
        listOf(ArchRule("leaf-unit:domain", "leaf-unit", "domain 은 다른 단위에 의존하지 않는다", mapOf("unit" to listOf("domain")), severity, Evidence(1, 1), approved = true)), emptyList(),
    ))

    @Test
    fun `아키텍처 규칙은 커밋된 코드의 기존 위반을 두고 이번 변경이 만든 위반만 막는다`() {
        val repo = repo(sources)
        val store = store(repo)
        architecture(store, "BLOCK")
        repo.resolve("src/main/kotlin/com/x/domain/D.kt").writeText("package com.x.domain\n\nimport com.x.service.S\nimport com.x.service.T\n\nclass D\n")
        val logs = mutableListOf<String>()
        val validation = linkedMapOf<String, String>()

        val problems = StageGates(store, ArchitectureStore(store), ConventionStore(store), { _, _ -> "" }, logs::add).quickCheck(task, repo, validation)

        assertEquals("FAILED", validation["architecture"])
        val found = problems.single { it.startsWith("아키텍처 위반") }
        assertContains(found, "아키텍처 위반 1건")
        assertContains(found, "D.kt:4 ")
        assertTrue(logs.any { "기존 위반 1건" in it }, logs.toString())

        Shell.gitOk(repo, "checkout", "--", ".")
        val clean = linkedMapOf<String, String>()
        StageGates(store, ArchitectureStore(store), ConventionStore(store), { _, _ -> "" }, logs::add).quickCheck(task, repo, clean)
        assertEquals("PASSED", clean["architecture"], "기존 위반만 있으면 막지 않는다")
    }

    @Test
    fun `검토 등급 규칙의 새 위반은 막지 않고 독립 검토 근거로 남긴다`() {
        val repo = repo(sources)
        val store = store(repo)
        architecture(store, "REVIEW")
        repo.resolve("src/main/kotlin/com/x/domain/D.kt").writeText("package com.x.domain\n\nimport com.x.service.S\nimport com.x.service.T\n\nclass D\n")
        val validation = linkedMapOf<String, String>()
        val gates = StageGates(store, ArchitectureStore(store), ConventionStore(store), { _, _ -> "" }, {})

        val problems = gates.quickCheck(task, repo, validation)

        assertEquals("REVIEW", validation["architecture"])
        assertTrue(problems.none { it.startsWith("아키텍처 위반") }, problems.toString())
        assertContains(gates.reviewRuleHits.single(), "D.kt:4 ")
        assertContains(gates.reviewRuleHits.single(), "<leaf-unit:domain>")
    }

    @Test
    fun `컨벤션 규칙은 새 파일의 위반을 막고 기존 위반이 남은 파일을 고치면 막지 않고 검토로 넘긴다`() {
        val repo = repo(mapOf(
            "src/main/kotlin/com/x/A.kt" to "package com.x\n\nclass A {\n    fun a() {\n        println(\"a\")\n    }\n}\n",
            "src/main/kotlin/com/x/C.kt" to "package com.x\n\nclass C\n",
        ))
        val store = store(repo)
        ConventionStore(store).save("p", ConventionModel("demo", 2, 0, emptyMap(), listOf(
            ConventionRule("no-debug-output", "no-debug-output", "디버그 출력 금지", approved = true),
        )))
        repo.resolve("src/main/kotlin/com/x/A.kt").writeText("package com.x\n\nclass A {\n    fun a() {\n        println(\"a\")\n    }\n\n    fun b() = 1\n}\n")
        val validation = linkedMapOf<String, String>()
        val gates = StageGates(store, ArchitectureStore(store), ConventionStore(store), { _, _ -> "" }, {})

        val kept = gates.quickCheck(task, repo, validation)

        assertTrue(kept.none { it.startsWith("컨벤션 위반") }, kept.toString())
        assertEquals("REVIEW", validation["convention"])
        assertContains(gates.reviewRuleHits.single(), "A.kt:5 ")
        assertContains(gates.reviewRuleHits.single(), "원래 있던 위반")

        repo.resolve("src/main/kotlin/com/x/B.kt").writeText("package com.x\n\nclass B {\n    fun b() {\n        println(\"b\")\n    }\n}\n")
        val again = linkedMapOf<String, String>()
        val blocked = gates.quickCheck(task, repo, again)

        assertEquals("FAILED", again["convention"])
        val found = blocked.single { it.startsWith("컨벤션 위반") }
        assertContains(found, "컨벤션 위반 1건")
        assertContains(found, "B.kt:5 ")
    }
}
