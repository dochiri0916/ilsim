package com.dochiri.ilsim.runner.corpus

import com.dochiri.ilsim.runner.Shell
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CorpusTest {

    private fun snapshot(rules: List<RuleStat>, applicability: String = "APPLICABLE") =
        CorpusSnapshot("p", "abc", "FLAT", applicability, emptyList(), rules, emptyList())

    @Test
    fun `규칙 추가 삭제 변경과 적용 가능성 변화를 알려 준다`() {
        val old = snapshot(listOf(RuleStat("a", "BLOCK", 5, 5), RuleStat("b", "REVIEW", 3, 3)))
        val new = snapshot(listOf(RuleStat("a", "REVIEW", 5, 6), RuleStat("c", "BLOCK", 9, 9)), "PARTIAL")
        val diff = Corpus.diff(old, new)
        assertEquals(4, diff.size)
        assertTrue(diff.any { it.contains("APPLICABLE → PARTIAL") })
        assertTrue(diff.any { it.contains("추가: c") })
        assertTrue(diff.any { it.contains("삭제: b") })
        assertTrue(diff.any { it.contains("변경: a BLOCK 5/5 → REVIEW 5/6") })
        assertTrue(Corpus.diff(old, old).isEmpty())
    }

    @Test
    fun `기준을 저장한 뒤 같은 상태면 변화가 없고 코드가 바뀌면 건너뛴다`() {
        val base = Files.createTempDirectory("ilsim-corpus-base")
        val data = Files.createTempDirectory("ilsim-corpus-data")
        val project = base.resolve("sample").also { it.createDirectories() }
        project.resolve("src/main/java/com/x/service").createDirectories()
        listOf("A", "B", "C").forEach { n ->
            project.resolve("src/main/java/com/x/service/${n}Service.java").writeText("package com.x.service;\n\n@Service\npublic class ${n}Service {\n}\n")
        }
        Shell.gitOk(project, "init", "-q")
        Shell.gitOk(project, "-c", "user.email=t@t", "-c", "user.name=t", "add", ".")
        Shell.gitOk(project, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", "init")

        val corpus = Corpus(data, base)
        corpus.add("sample", "sample")
        corpus.update(emptyList())
        val (clean, lines) = corpus.check(emptyList())
        assertTrue(clean, lines.toString())

        project.resolve("src/main/java/com/x/service/DService.java").writeText("package com.x.service;\n\n@Service\npublic class DService {\n}\n")
        val (afterChange, changed) = corpus.check(emptyList())
        assertFalse(afterChange)
        assertTrue(changed.single().contains("기준 커밋"), changed.toString())
    }
}
