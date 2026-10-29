package com.dochiri.ilsim.runner

import com.dochiri.ilsim.runner.convention.ConventionRule
import com.dochiri.ilsim.runner.convention.DocCoverage
import com.dochiri.ilsim.runner.convention.DocRuleStore
import com.dochiri.ilsim.runner.convention.DocRules
import com.dochiri.ilsim.runner.document.ProjectDocs
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalysisGapsTest {
    private lateinit var home: Path
    private lateinit var repo: Path
    private lateinit var store: ConfigStore

    @BeforeTest
    fun setUp() {
        home = Files.createTempDirectory("ilsim-gaps-home")
        repo = Files.createTempDirectory("ilsim-gaps-repo").toRealPath()
        repo.resolve("docs").createDirectories()
        repo.resolve("docs/conventions.md").writeText("# 컨벤션\n\n## 이름\n\n- 서비스 클래스는 Service로 끝낸다.\n\n## 소개\n\n이 문서는 컨벤션을 모은다.\n")
        Shell.gitOk(repo, "init", "-q", "-b", "main")
        Shell.gitOk(repo, "add", "-A")
        store = ConfigStore(home.resolve(".ilsim/runner.json"))
        store.save(RunnerConfig(runnerId = "r1", projects = mapOf("p" to ProjectEntry(repo.toString(), "demo"))))
    }

    @AfterTest
    fun tearDown() {
        home.toFile().deleteRecursively()
        repo.toFile().deleteRecursively()
    }

    private fun docs() = projectDocs(store.load(), "p", repo, store.load().projects.getValue("p"))

    @Test
    fun `분석이 없거나 문서 규칙 구간이 바뀌거나 문서가 새로 생기면 빈틈으로 알린다`() {
        assertEquals(listOf("아키텍처 분석이 없습니다", "컨벤션 분석이 없습니다", "문서 지침 검증기가 없습니다"), analysisGaps(store, "p", docs()))
        analyzeArchitecture(store, "p", StringBuilder())
        analyzeConvention(store, "p", StringBuilder())
        val rule = ProjectDocs.sections(docs()).single { it.heading.endsWith("이름") }
        val model = DocRules.DocRuleModel(documents = listOf(DocCoverage(rule.path, rule.line, rule.heading, DocRules.sectionHash(rule), emptyList(), emptyList())), files = docs().keys.sorted())
        DocRuleStore(store).save("p", model)
        assertEquals(emptyList(), analysisGaps(store, "p", docs()))

        repo.resolve("docs/conventions.md").writeText("# 컨벤션\n\n## 이름\n\n- 서비스 클래스는 Service로 끝낸다.\n\n## 소개\n\n소개만 고쳤다.\n")
        assertEquals(emptyList(), analysisGaps(store, "p", docs()), "규칙이 아닌 구간만 바뀌면 다시 분석하지 않는다")

        repo.resolve("docs/conventions.md").writeText("# 컨벤션\n\n## 이름\n\n- 서비스 클래스는 UseCase로 끝낸다.\n")
        repo.resolve("docs/testing.md").writeText("# 테스트\n\n- 테스트 메서드마다 한국어 DisplayName을 붙인다.\n")
        Shell.gitOk(repo, "add", "-A")
        val gaps = analysisGaps(store, "p", docs())
        assertTrue(gaps.any { it == "분석 뒤 생긴 문서 docs/testing.md" }, gaps.toString())
        assertTrue(gaps.any { it.startsWith("분석 뒤 바뀌거나 사라졌거나 검증기 버전이 달라진 문서 규칙 구간 docs/conventions.md:3") }, gaps.toString())

        assertEquals(emptyMap(), pendingRules(store, "p"))
        DocRuleStore(store).save("p", model.copy(rules = listOf(ConventionRule("doc:1", "doc-forbidden-code", "System.out 을 쓰지 않는다"), ConventionRule("doc:2", "doc-forbidden-code", "승인됨", approved = true))))
        assertEquals(mapOf("문서 규칙" to 1), pendingRules(store, "p"))
        DocRuleStore(store).save("p", model.copy(files = emptyList()))
        assertTrue(analysisGaps(store, "p", docs()).any { "예전 형식" in it })
        DocRuleStore(store).save("p", DocRules.DocRuleModel(files = emptyList(), applicable = false))
        assertEquals(emptyList(), analysisGaps(store, "p", docs()), "정적 검증기로 옮길 수 없는 프로젝트는 문서 변경으로 막지 않는다")
    }
}
