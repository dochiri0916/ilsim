package com.dochiri.ilsim.runner

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 일심에 등록한 Claude 계정: 상태 읽기, 로그인 URL, 사용량 기준 선택, 세션의 계정 표시, 공통 설정 적용. */
class ClaudeAccountsTest {

    private lateinit var root: Path
    private lateinit var previousRoot: Path

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("ilsim-accounts")
        previousRoot = ClaudeAccounts.root
        ClaudeAccounts.root = root
    }

    @AfterTest
    fun tearDown() {
        ClaudeAccounts.root = previousRoot
        ClaudeAccounts.environment = null
        root.toFile().deleteRecursively()
    }

    @Test
    fun `auth status 에서 로그인·이메일·구독을 읽고 구독 없는 계정은 쓰지 않는다`() {
        val pro = ClaudeAccounts.parseStatus("a", """{"loggedIn": true, "authMethod": "claude.ai", "email": "a@x.com", "subscriptionType": "pro"}""")
        assertEquals(ClaudeAccountReport("a", true, "a@x.com", "pro"), pro)
        assertTrue(pro.usable)
        assertFalse(ClaudeAccounts.parseStatus("b", """{"loggedIn": true, "email": "b@x.com", "subscriptionType": null}""").usable)
        assertEquals(ClaudeAccountReport("c", false), ClaudeAccounts.parseStatus("c", "Not logged in"))
    }

    @Test
    fun `터미널 링크 제어 문자가 섞인 출력에서 로그인 URL 만 뽑는다`() {
        val out = "Opening browser to sign in…\nIf the browser didn't open, visit: \u001b]8;;https://claude.com/cai/oauth/authorize?code=true&client_id=x&state=y\u0007https://claude.com/cai/oauth/authorize?code=true\u001b]8;;\u0007\nPaste code here if prompted > "
        assertEquals("https://claude.com/cai/oauth/authorize?code=true&client_id=x&state=y", ClaudeAccounts.loginUrl(out))
        assertNull(ClaudeAccounts.loginUrl("Opening browser to sign in…"))
    }

    @Test
    fun `새 세션은 5시간·주간 중 더 빡빡한 쪽이 가장 여유 있는 계정으로 간다`() {
        listOf("a", "b").forEach { root.resolve(it).createDirectories() }
        val usage = mapOf(
            "a" to PlanUsageReport(fiveHour = UsageWindowReport(0.2), sevenDay = UsageWindowReport(0.9)),
            "b" to PlanUsageReport(fiveHour = UsageWindowReport(0.6), sevenDay = UsageWindowReport(0.3)),
        )
        withStates(listOf(ClaudeAccountReport("a", true, plan = "pro"), ClaudeAccountReport("b", true, plan = "pro"))) {
            assertEquals("b", ClaudeAccounts.pick { usage[it] })
        }
        withStates(listOf(ClaudeAccountReport("a", true, plan = "pro"), ClaudeAccountReport("b", true, plan = null))) {
            assertEquals("a", ClaudeAccounts.pick { usage[it] })
        }
        withStates(emptyList()) { assertNull(ClaudeAccounts.pick { null }) }
    }

    @Test
    fun `세션 id 에 계정을 붙이고 실행 환경은 계정 디렉터리와 구독 인증만 쓴다`() {
        ClaudeAccountScope.within("work") {
            assertEquals("claude@work:s-1", ClaudeAccountScope.tag("s-1"))
            val env = mutableMapOf("ANTHROPIC_API_KEY" to "k", "PATH" to "/bin")
            ClaudeAccountScope.configure(env)
            assertEquals(root.resolve("work").toString(), env["CLAUDE_CONFIG_DIR"])
            assertFalse("ANTHROPIC_API_KEY" in env)
        }
        assertEquals("s-1", ClaudeAccountScope.tag("s-1"))
        assertEquals("work", ClaudeAccountScope.accountOf("claude@work:s-1"))
        assertNull(ClaudeAccountScope.accountOf("s-1"))
        assertEquals("s-1", ClaudeAccountScope.rawSession("claude@work:s-1"))
        assertEquals("claude@work", ClaudeAccountScope.provider("work"))
        assertEquals("claude", ClaudeAccountScope.provider(null))
        assertTrue(ProcessClaudeAgent.command(AssignedTask("t", "p", "REQUIREMENTS", 1, "f", ClaudeModels.SONNET, "medium", "x", resumeSessionId = "claude@work:s-1"), "x")
            .windowed(2).contains(listOf("--resume", "s-1")))
    }

    @Test
    fun `공통 설정을 계정 디렉터리에 쓰고 버전이 같으면 다시 쓰지 않는다`() {
        val dir = root.resolve("a").createDirectories()
        ClaudeAccounts.environment = ClaudeEnvironmentReport("# 규칙\n한국어", """{"model":"opus"}""", "v1")
        ClaudeAccounts.applyEnvironment("a")
        assertEquals("# 규칙\n한국어", dir.resolve("CLAUDE.md").readText())
        assertEquals("""{"model":"opus"}""", dir.resolve("settings.json").readText())

        Files.writeString(dir.resolve("CLAUDE.md"), "직접 고침")
        ClaudeAccounts.applyEnvironment("a")
        assertEquals("직접 고침", dir.resolve("CLAUDE.md").readText())

        ClaudeAccounts.environment = ClaudeEnvironmentReport("새 규칙", "{}", "v2")
        ClaudeAccounts.applyEnvironment("a")
        assertEquals("새 규칙", dir.resolve("CLAUDE.md").readText())
    }

    /** 실제 `claude auth status` 를 부르지 않고 계정 상태를 채운다. */
    private fun withStates(states: List<ClaudeAccountReport>, action: () -> Unit) {
        val field = ClaudeAccounts::class.java.getDeclaredField("states").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val map = field.get(ClaudeAccounts) as MutableMap<String, ClaudeAccountReport>
        map.clear()
        states.forEach { map[it.name] = it }
        try { action() } finally { map.clear() }
    }
}
