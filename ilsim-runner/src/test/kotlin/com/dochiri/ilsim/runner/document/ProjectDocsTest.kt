package com.dochiri.ilsim.runner.document

import com.dochiri.ilsim.runner.ProjectEntry
import com.dochiri.ilsim.runner.Shell
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectDocsTest {
    private lateinit var repo: Path
    private lateinit var outside: Path

    @BeforeTest
    fun setUp() {
        repo = Files.createTempDirectory("ilsim-docs").toRealPath()
        outside = Files.createTempDirectory("ilsim-docs-out").toRealPath()
        Shell.gitOk(repo, "init", "-q", "-b", "main")
        mapOf(
            "README.md" to "# 소개\n\n데모\n",
            "docs/architecture.adoc" to "= 구조\n\n도메인은 어댑터를 모른다.\n",
            "docs/old/legacy.md" to "# 예전\n\n안 씀\n",
            "specs/slug/requirements.md" to "REQ-1 소문자\n",
            "specs/prev/requirements.md" to "REQ-1 이전 기능\n",
            "sub/GUIDE.md" to "# 다른 프로젝트\n\n섞이면 안 됨\n",
            "src/Main.kt" to "fun main() {}\n",
            ".gitignore" to "node_modules/\n",
        ).forEach { (path, text) -> repo.resolve(path).also { it.parent.createDirectories() }.writeText(text) }
        Shell.gitOk(repo, "add", "-A")
        repo.resolve("node_modules/pkg").createDirectories()
        repo.resolve("node_modules/pkg/README.md").writeText("# 패키지\n\n무시\n")
        repo.resolve("UNTRACKED.md").writeText("# 미추적\n\n무시\n")
        outside.resolve("team.md").writeText("# 팀 규칙\n\n주석 금지\n")
    }

    @AfterTest
    fun tearDown() {
        repo.toFile().deleteRecursively()
        outside.toFile().deleteRecursively()
    }

    @Test
    fun `그 프로젝트가 추적하는 문서와 추가한 문서만 쓰고 다른 프로젝트·현재 산출물·제외 경로는 뺀다`() {
        val project = ProjectEntry(repo.toString(), "demo", docs = listOf(outside.resolve("team.md").toString()), docsExclude = listOf("docs/old/"))
        val files = ProjectDocs.files(repo, project, listOf(repo.resolve("sub"), outside.resolve("elsewhere")), "slug")
        assertEquals(listOf("README.md", "docs/architecture.adoc", "specs/prev/requirements.md", outside.resolve("team.md").toString()).sorted(), files.keys.toList())
    }

    @Test
    fun `제목 단위로 나누고 코드 블록 안의 샵은 제목으로 보지 않으며 긴 구간은 자른다`() {
        val text = "# 규칙\n\n## 계층\n도메인 순수\n```bash\n# 주석\necho x\n```\n\n## 비어 있음\n\n# 테스트\n" + "가".repeat(ProjectDocs.MAX_SECTION + 10) + "\n"
        val sections = ProjectDocs.split("a.md", text)
        assertEquals("규칙 > 계층", sections[0].heading)
        assertEquals(3, sections[0].line)
        assertTrue("# 주석" in sections[0].text)
        assertTrue(sections.none { it.heading.endsWith("비어 있음") })
        assertTrue(sections.drop(1).all { it.heading == "테스트" && it.text.length <= ProjectDocs.MAX_SECTION })
        assertEquals(3, sections.size)
        assertEquals("구조", ProjectDocs.split("b.adoc", "= 구조\n\n----\n= 아님\n----\n").single().heading)
    }
}
