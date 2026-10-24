package com.dochiri.ilsim.runner

import com.dochiri.ilsim.runner.audit.ChangeAudit
import java.nio.file.Files
import kotlin.test.*

class CostOptimizationTest {
    @Test
    fun `모델별 누적 사용량과 실패 결과 비용을 읽고 도구 접근은 명시적으로 제한한다`() {
        val output = """{"type":"result","session_id":"s","total_cost_usd":0.15,"modelUsage":{"claude-sonnet-5-5":{"costUSD":0.15,"inputTokens":100,"outputTokens":20,"cacheReadInputTokens":50,"cacheCreationInputTokens":10}}}"""
        val usage = ClaudeUsage.parse(output, ClaudeModels.SONNET, "low", "WRITE", "2.1.302").single()
        assertTrue(usage.cumulative)
        assertEquals(0.15, usage.costUsd)
        assertEquals(50L, usage.cacheReadTokens)
        assertFalse(ClaudeUsage.parse(output, ClaudeModels.SONNET, "low", "WRITE", "2.1.276").single().cumulative)
        val recovered = ClaudeUsage.assistant("""{"type":"assistant","message":{"id":"m","model":"claude-sonnet-5-5","usage":{"input_tokens":30,"output_tokens":1,"cache_read_input_tokens":10}}}""")!!
        assertEquals(30L, recovered.inputTokens)
        assertEquals(0L, recovered.outputTokens)
        assertEquals("RECOVERED", recovered.status)
        assertEquals("UNKNOWN", ClaudeUsage.parse("""{"type":"result","subtype":"error_during_execution","total_cost_usd":0}""", ClaudeModels.SONNET, "low", "WRITE", "2.1.302").single().status)
        val command = ClaudeCli.command("p", ClaudeModels.HAIKU, "low")
        assertEquals("", command[command.indexOf("--tools") + 1])
        assertEquals("mcp__*", command[command.indexOf("--disallowedTools") + 1])
        assertTrue("--strict-mcp-config" in command)
        val tally = CostTally { _, _ -> throw ClaudeCli.Failure(ClaudeCli.Answer("error", 0.07), "error") }
        assertFailsWith<ClaudeCli.Failure> { tally.ask("p", "m") }
        assertEquals(0.07, tally.usd)
    }

    @Test
    fun `검토의 빈 응답은 통과로 캐시하지 않고 diff와 테스트 뒷부분을 보존한다`() {
        assertFailsWith<IllegalArgumentException> { ChangeAudit.parse("{}") }
        assertFailsWith<IllegalArgumentException> { ChangeAudit.parse("""{"verdict":"CONCERN","findings":[]}""") }
        val prompt = ChangeAudit.prompt("IMPLEMENTATION", "r".repeat(7000) + "REQ-END", "t".repeat(7000) + "TC-END", null, emptyMap(), "d".repeat(30_000) + "DIFF-END",
            committedTests = mapOf("A.kt" to "a".repeat(12_000), "Relevant.kt" to "RELEVANT-END"))
        assertTrue(listOf("REQ-END", "TC-END", "DIFF-END", "RELEVANT-END").all { it in prompt })
        val dir = Files.createTempDirectory("ilsim-cache")
        try {
            val cache = ReviewCache(dir); var calls = 0
            assertFailsWith<IllegalArgumentException> { cache.get("p", "m/low", ChangeAudit::parse) { calls++; "{}" } }
            repeat(2) { cache.get("p", "m/low", ChangeAudit::parse) { calls++; """{"verdict":"PASS","findings":[]}""" } }
            assertEquals(2, calls)
            cache.get("p", "m/high", ChangeAudit::parse) { calls++; """{"verdict":"PASS","findings":[]}""" }
            assertEquals(3, calls)
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test
    fun `큰 검토 입력도 명령행 길이 제한 없이 stdin으로 전달한다`() {
        val dir = Files.createTempDirectory("ilsim-stdin")
        try {
            val prompt = "전체 검토 근거\n".repeat(20_000)
            val result = Shell.run(dir, "cat", maxChars = prompt.length + 10, stdin = prompt)
            assertEquals(0, result.exit)
            assertEquals(prompt, result.output)
        } finally { dir.toFile().deleteRecursively() }
    }
}
