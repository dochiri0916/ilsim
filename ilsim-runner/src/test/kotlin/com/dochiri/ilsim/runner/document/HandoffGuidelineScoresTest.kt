package com.dochiri.ilsim.runner.document

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HandoffGuidelineScoresTest {
    private val lines = Files.readAllLines(Path.of("..", HANDOFF))

    private val headings = lines.filter { it.startsWith("## ") }

    private fun heading() = headings.single { "bb013d57" in it }

    private fun section(): String {
        val start = lines.indexOf(heading())
        val rest = lines.drop(start + 1).takeWhile { !it.startsWith("## ") }
        return (listOf(lines[start]) + rest).joinToString("\n")
    }

    @Test
    fun `TC-1 섹션 제목에 날짜 작업 ID 브랜치와 미병합 상태가 있다`() {
        val title = heading()
        assertTrue("2026-10-25" in title)
        assertTrue("ilsim/guidelines-final-doc-scores-bb013d57" in title)
        assertTrue("main 병합 안 함" in title)
    }

    @Test
    fun `TC-2 바뀐 동작이 모두 적혀 있다`() {
        val body = section()
        val expected = listOf("최종 관련도", "최고값", "선택 구간 수", "관련도 내림차순", "경로 오름차순", "0.2 이하", "참조", "중복")
        expected.forEach { assertTrue(it in body, it) }
    }

    @Test
    fun `TC-3 거름 점수를 출력하지 않는 사용자 결정이 있다`() {
        val body = section()
        assertTrue("사용자 결정" in body)
        assertTrue("거름 점수" in body && "출력하지 않는다" in body)
    }

    @Test
    fun `TC-4 추가 함수와 select 유지가 적혀 있다`() {
        val body = section()
        listOf("Guidelines.selection", "documentScores", "documentLine", "select").forEach { assertTrue(it in body, it) }
        assertTrue("그대로" in body)
    }

    @Test
    fun `TC-5 검증 결과가 적혀 있다`() {
        val body = section()
        val expected = listOf("4단계 통과", "tests PASSED", "requirements UNCERTAIN", ":ilsim-runner:test", "실제 JEV")
        expected.forEach { assertTrue(it in body, it) }
    }

    @Test
    fun `TC-6 섹션은 2026-10-24 섹션 다음 사용자 확인 대기 바로 앞에 있다`() {
        val i = headings.indexOf(heading())
        assertTrue(headings[i - 1].startsWith("## 2026-10-24 JEV 위험 선별"))
        assertEquals("## 사용자 확인 대기", headings[i + 1])
    }

    @Test
    fun `TC-7 섹션 제목은 하나다`() {
        assertEquals(1, headings.count { "bb013d57" in it })
    }

    private fun git(vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args).directory(java.io.File(".."))
            .redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), out)
        return out
    }

    private fun added(): String? =
        git("log", "--reverse", "--format=%H", "-S", "bb013d57", "--", HANDOFF).lines().firstOrNull { it.isNotBlank() }

    private fun handoffDiff(): String =
        added()?.let { git("show", "--format=", it, "--", HANDOFF) } ?: git("diff", "HEAD", "--", HANDOFF)

    private fun changedFiles(): List<String> {
        val names = added()?.let { git("show", "--format=", "--name-only", it) } ?: git("diff", "HEAD", "--name-only")
        return names.lines().filter { it.isNotBlank() }
    }

    @Test
    fun `TC-8 인수인계 변경은 추가 줄뿐이다`() {
        val diff = handoffDiff().lines()
        assertTrue(diff.any { it.startsWith("+") && !it.startsWith("+++") && "bb013d57" in it })
        assertTrue(diff.none { it.startsWith("-") && !it.startsWith("---") })
    }

    @Test
    fun `TC-11 섹션을 넣은 변경은 인수인계와 이 작업 산출물뿐이다`() {
        val files = changedFiles()
        assertTrue(HANDOFF in files)
        val allowed = files.all {
            it == HANDOFF || it.startsWith("specs/handoff-guidelines-doc-scores/") ||
                it.endsWith("/HandoffGuidelineScoresTest.kt")
        }
        assertTrue(allowed, files.toString())
    }

    @Test
    fun `TC-9 마지막 갱신 줄은 그대로다`() {
        assertTrue(lines.any { it.startsWith("마지막 갱신: 2026-10-20") })
    }

    @Test
    fun `TC-10 섹션에 민감 정보와 PC 절대 경로가 없다`() {
        val body = section()
        assertFalse("/Users/" in body)
        assertFalse("~/" in body)
        assertFalse(Regex("(?i)(password|token|api[_-]?key)\\s*[:=]").containsMatchIn(body))
    }

    private companion object {
        const val HANDOFF = "docs/handoff.md"
    }
}
