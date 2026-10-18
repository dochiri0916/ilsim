package com.dochiri.ilsim.runner.document

import com.dochiri.ilsim.runner.architecture.ArchRule
import com.dochiri.ilsim.runner.architecture.ArchitectureModel
import com.dochiri.ilsim.runner.architecture.ArchitectureReviewer
import com.dochiri.ilsim.runner.architecture.Evidence
import com.dochiri.ilsim.runner.architecture.Layout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DocumentsTest {

    private fun project(vararg files: Pair<String, String>): Path {
        val root = Files.createTempDirectory("ilsim-doc")
        files.forEach { (p, t) -> root.resolve(p).also { it.parent.createDirectories() }.writeText(t) }
        return root
    }

    private val arch = "# 아키텍처\n\n## 계층\n\ndomain 은 adapter 에 의존하지 않는다.\n\n## 일정\n\n다음 분기에 출시한다.\n"
    private val style = "# Coding style\n\n## Naming\n\n컨트롤러 이름은 Controller 로 끝난다.\n"

    @Test
    fun `목적별로 다른 문서를 고르고 빌드 산출물과 무관한 문서는 제외한다`() {
        val root = project(
            "docs/ARCHITECTURE.md" to arch,
            "docs/CODING-STYLE.md" to style,
            "README.md" to "# 소개\n\n설치 방법\n",
            "build/docs/architecture.md" to arch,
        )
        assertEquals(listOf("docs/ARCHITECTURE.md"), DocumentContext.build(root, DocPurpose.ARCHITECTURE).refs.map { it.path })
        assertEquals(listOf("docs/CODING-STYLE.md"), DocumentContext.build(root, DocPurpose.CONVENTION).refs.map { it.path })
    }

    @Test
    fun `관련 절만 발췌한다`() {
        val ctx = DocumentContext.build(project("docs/architecture.md" to arch), DocPurpose.ARCHITECTURE)
        assertTrue(ctx.text.contains("domain 은 adapter 에 의존하지 않는다"))
        assertTrue(!ctx.text.contains("다음 분기"), "일정 절은 제외")
        assertTrue(DocumentContext.build(project("README.md" to "# 소개\n"), DocPurpose.ARCHITECTURE).isEmpty)
    }

    @Test
    fun `문서가 있으면 검토 기준과 발췌를 프롬프트에 넣고 문서가 바뀌면 다시 검토한다`() {
        val model = ArchitectureModel(
            "p", 1, Layout("com.x", "FLAT"), emptyList(), emptyList(), emptyList(), emptyList(),
            listOf(ArchRule("a", "leaf-unit", "d", emptyMap(), "BLOCK", Evidence(1, 1))), emptyList(),
        )
        val docs = DocumentContext.build(project("docs/architecture.md" to arch), DocPurpose.ARCHITECTURE)
        val prompt = ArchitectureReviewer.prompt(model, docs)
        assertTrue(prompt.contains("검토 기준: ${DocPurpose.ARCHITECTURE.criteria}") && prompt.contains("domain 은 adapter"))
        assertTrue(!ArchitectureReviewer.prompt(model).contains("프로젝트 문서"))

        var calls = 0
        val ask = { _: String -> calls++; """{"rules":[{"id":"a","verdict":"KEEP","reason":"문서와 일치"}],"missing":[],"applicability":"문서 확인"}""" }
        val first = ArchitectureReviewer.review(model, "sonnet", docs = docs, ask = ask)
        ArchitectureReviewer.review(first, "sonnet", docs = docs, ask = ask)
        assertEquals(1, calls)
        val changed = DocumentContext.build(project("docs/architecture.md" to arch.replace("않는다", "않는다 (예외 없음)")), DocPurpose.ARCHITECTURE)
        ArchitectureReviewer.review(first, "sonnet", docs = changed, ask = ask)
        assertEquals(2, calls, "문서가 바뀌면 같은 규칙이어도 다시 검토")
    }

    @Test
    fun `선택 기준은 교체할 수 있다`() {
        val onlyReadme = object : DocumentSelector {
            override fun select(purpose: DocPurpose, candidates: List<DocumentFile>) = candidates.filter { it.path == "README.md" }.map { DocumentRef(it.path, 10, "JEV") }
        }
        val ctx = DocumentContext.build(project("README.md" to "# 아키텍처\n\n계층 구조\n", "docs/architecture.md" to arch), DocPurpose.ARCHITECTURE, onlyReadme)
        assertEquals(listOf("README.md"), ctx.refs.map { it.path })
    }
}
