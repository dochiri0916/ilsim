package com.dochiri.ilsim.runner

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderAccountsTest {
    private lateinit var home: Path
    private val savedRoot = ClaudeAccounts.root
    private val savedHome = ProviderAccounts.home

    @BeforeTest
    fun setUp() {
        home = Files.createTempDirectory("ilsim-provider")
        ClaudeAccounts.root = home.resolve("claude-accounts")
        ProviderAccounts.home = home
        ClaudeAccounts.dir("deepseek").createDirectories()
        ClaudeAccounts.dir("deepseek").resolve("provider.json")
            .writeText("""{"provider":"deepseek","baseUrl":"https://api.deepseek.com/anthropic","model":"deepseek-flash"}""")
        ClaudeAccounts.dir("personal").createDirectories()
    }

    @AfterTest
    fun tearDown() {
        ClaudeAccounts.root = savedRoot
        ProviderAccounts.home = savedHome
    }

    private fun task(model: String = "claude-sonnet-5-5", effort: String = "medium", attempt: Int = 1) =
        AssignedTask("t", "p", "IMPLEMENTATION", attempt, "f", model, effort, "prompt")

    @Test
    fun `키가 있어야 공급자 계정을 쓸 수 있다`() {
        assertNull(ProviderAccounts.usableName())
        home.resolve("deepseek.key").writeText("sk-test\n")
        assertEquals("deepseek", ProviderAccounts.usableName())
        assertNull(ProviderAccounts.config("personal"))
    }

    @Test
    fun `첫 시도의 쉬운 작업만 공급자로 보낸다`() {
        val c = ProviderAccounts.config("deepseek")!!
        assertTrue(ProviderAccounts.eligible(task(), c))
        assertTrue(ProviderAccounts.eligible(task("claude-haiku-5-5", "low"), c))
        assertFalse(ProviderAccounts.eligible(task("claude-opus-5-5"), c))
        assertFalse(ProviderAccounts.eligible(task(effort = "high"), c))
        assertFalse(ProviderAccounts.eligible(task(attempt = 2), c))
    }

    @Test
    fun `공급자 계정은 환경 변수와 모델과 노력 수준을 바꾸고 비용을 토큰으로 계산한다`() {
        home.resolve("deepseek.key").writeText("sk-test")
        ClaudeAccountScope.within("deepseek") {
            val env = mutableMapOf("ANTHROPIC_API_KEY" to "x", "CLAUDE_CODE_OAUTH_TOKEN" to "y")
            ClaudeAccountScope.configure(env)
            assertEquals("https://api.deepseek.com/anthropic", env["ANTHROPIC_BASE_URL"])
            assertEquals("sk-test", env["ANTHROPIC_AUTH_TOKEN"])
            assertEquals("deepseek-flash", env["ANTHROPIC_DEFAULT_SONNET_MODEL"])
            assertNull(env["ANTHROPIC_API_KEY"])
            assertNull(env["CLAUDE_CODE_OAUTH_TOKEN"])
            val adapted = ProviderAccounts.adapt(task())
            assertEquals("deepseek-flash", adapted.model)
            assertEquals("max", adapted.effort)
            val priced = ProviderAccounts.priced(listOf(AiUsage("u", "WRITE", "deepseek-flash", inputTokens = 1_000_000, outputTokens = 1_000_000, cacheReadTokens = 1_000_000)))
            assertEquals(0.14 + 0.28 + 0.0028, priced.single().costUsd!!, 1e-9)
        }
        ClaudeAccountScope.within("personal") {
            assertEquals("claude-sonnet-5-5", ProviderAccounts.adapt(task()).model)
            val usage = listOf(AiUsage("u", "WRITE", "m", costUsd = 1.0))
            assertTrue(ProviderAccounts.priced(usage) === usage)
        }
    }

    @Test
    fun `공급자 계정은 구독 계정 선택과 사용량 확인 대상에서 빠진다`() {
        home.resolve("deepseek.key").writeText("sk-test")
        val report = ClaudeAccounts.status("deepseek")
        assertTrue(report.usable)
        assertNotNull(report.plan)
        ClaudeAccounts.refreshForTest(listOf(report, ClaudeAccountReport("personal", true, "a@b", "pro")))
        assertEquals(listOf("personal"), ClaudeAccounts.usable())
        assertEquals(listOf("deepseek", "personal"), ClaudeAccounts.usableAny())
    }
}
