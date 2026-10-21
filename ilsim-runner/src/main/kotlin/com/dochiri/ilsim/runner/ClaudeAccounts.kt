package com.dochiri.ilsim.runner

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class ClaudeEnvironmentReport(
    val claudeMd: String = "",
    val settingsJson: String = "{}",
    val version: String = "",
    val skills: List<ClaudeSkillReport> = emptyList(),
)

@Serializable
data class ClaudeAccountReport(val name: String, val loggedIn: Boolean, val email: String? = null, val plan: String? = null) {
    val usable: Boolean get() = loggedIn && !plan.isNullOrBlank()
}

object ClaudeAccounts {
    var root: Path = Path.of(System.getProperty("user.home"), ".ilsim", "claude-accounts")
    private val states = ConcurrentHashMap<String, ClaudeAccountReport>()
    @Volatile var environment: ClaudeEnvironmentReport? = null
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val tmp: Path get() = Path.of(System.getProperty("java.io.tmpdir"))

    fun dir(name: String): Path = root.resolve(name)

    fun names(): List<String> = if (!root.isDirectory()) emptyList()
        else Files.list(root).use { s -> s.filter { it.isDirectory() }.map { it.fileName.toString() }.sorted().toList() }

    fun refresh(): List<ClaudeAccountReport> {
        val now = names().map { name -> ClaudeAccountScope.within(name) { status(name) } }
        states.keys.retainAll(now.map { it.name }.toSet())
        now.forEach { states[it.name] = it }
        return now
    }

    fun status(name: String): ClaudeAccountReport {
        ProviderAccounts.config(name)?.let { return ClaudeAccountReport(name, ProviderAccounts.key(it) != null, it.provider, "api") }
        val r = runCatching { Shell.run(tmp, "claude", "auth", "status", "--json", timeoutMinutes = 1) }.getOrNull()
        return parseStatus(name, r?.output.orEmpty())
    }

    internal fun parseStatus(name: String, output: String): ClaudeAccountReport {
        val obj = runCatching { json.parseToJsonElement(output.substring(output.indexOf('{').coerceAtLeast(0))) as JsonObject }.getOrNull()
            ?: return ClaudeAccountReport(name, false)
        fun text(key: String) = obj[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
        return ClaudeAccountReport(name, obj["loggedIn"]?.jsonPrimitive?.booleanOrNull == true, text("email"), text("subscriptionType"))
    }

    fun usable(): List<String> = states.values.filter { it.usable && ProviderAccounts.config(it.name) == null }.map { it.name }.sorted()

    internal fun refreshForTest(reports: List<ClaudeAccountReport>) {
        states.clear()
        reports.forEach { states[it.name] = it }
    }

    fun usableAny(): List<String> = states.values.filter { it.usable }.map { it.name }.sorted()

    fun pick(usage: (String) -> PlanUsageReport? = { PlanUsageWatch.latest(ClaudeAccountScope.provider(it)) }): String? =
        usable().minByOrNull { name -> usage(name)?.let { maxOf(it.fiveHour?.utilization ?: 0.0, it.sevenDay?.utilization ?: 0.0) } ?: 0.0 }

    fun applyEnvironment(name: String) {
        val env = environment ?: return
        val dir = dir(name)
        if (!dir.exists()) return
        val marker = dir.resolve(".ilsim-environment")
        if (marker.exists() && marker.readText() == env.version) return
        KnowledgeFiles.write(dir.resolve("CLAUDE.md"), env.claudeMd)
        KnowledgeFiles.write(dir.resolve("settings.json"), env.settingsJson)
        ClaudeSkills.install(dir.resolve("skills"), env.skills)
        marker.writeText(env.version)
    }

    fun remove(name: String) {
        val dir = dir(name)
        if (!dir.exists()) return
        ClaudeAccountScope.within(name) { runCatching { Shell.run(tmp, "claude", "auth", "logout", timeoutMinutes = 1) } }
        dir.toFile().deleteRecursively()
        states.remove(name)
    }

    fun login(name: String, onUrl: (String) -> Unit, nextCode: () -> String?, timeoutMinutes: Long = 15): ClaudeAccountReport {
        val dir = dir(name)
        Files.createDirectories(dir)
        applyEnvironment(name)
        val builder = ClaudeAccountScope.within(name) { Shell.process(tmp, listOf("claude", "auth", "login", "--claudeai")) }
        builder.environment()["BROWSER"] = "/usr/bin/true"
        val p = builder.start()
        val out = StringBuilder()
        val reader = Thread { runCatching { p.inputStream.bufferedReader().forEachLine { synchronized(out) { out.appendLine(it) } } } }.apply { isDaemon = true; start() }
        val deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(timeoutMinutes)
        try {
            var url: String? = null
            while (p.isAlive && System.currentTimeMillis() < deadline) {
                if (url == null) url = synchronized(out) { loginUrl(out.toString()) }?.also(onUrl)
                else nextCode()?.let { code -> p.outputStream.bufferedWriter().apply { write(code + "\n"); flush() } }
                Thread.sleep(1000)
            }
            if (p.isAlive) p.destroyForcibly()
            reader.join(2000)
        } finally {
            if (p.isAlive) p.destroyForcibly()
        }
        val state = ClaudeAccountScope.within(name) { status(name) }
        states[name] = state
        return state
    }

    internal fun loginUrl(output: String): String? = Regex("""https://[^\s\u0007\u001b"'<>]*oauth/authorize[^\s\u0007\u001b"'<>]*""").find(output)?.value
}

object ClaudeAccountScope {
    private val current = ThreadLocal<String?>()

    val account: String? get() = current.get()

    fun provider(name: String?): String = name?.let { "claude@$it" } ?: "claude"

    fun <T> within(name: String?, action: () -> T): T {
        val previous = current.get()
        current.set(name)
        return try {
            name?.let(ClaudeAccounts::applyEnvironment)
            action()
        } finally { current.set(previous) }
    }

    fun configure(env: MutableMap<String, String>) {
        val name = current.get() ?: return
        env["CLAUDE_CONFIG_DIR"] = ClaudeAccounts.dir(name).toString()
        env.remove("ANTHROPIC_API_KEY")
        env.remove("ANTHROPIC_AUTH_TOKEN")
        env.remove("CLAUDE_CODE_OAUTH_TOKEN")
        ProviderAccounts.config(name)?.let { ProviderAccounts.environment(it, env) }
    }

    fun tag(sessionId: String?): String? = sessionId?.let { id -> current.get()?.let { "claude@$it:$id" } ?: id }

    fun accountOf(sessionId: String): String? = Regex("^claude@([a-z0-9][a-z0-9-]*):").find(sessionId)?.groupValues?.get(1)

    fun rawSession(sessionId: String): String = sessionId.replace(Regex("^claude@[a-z0-9][a-z0-9-]*:"), "")
}

class ClaudeLoginWorker(private val client: ServerClient, private val runnerId: String) {
    fun start(): Thread = Thread(::loop, "claude-login").apply { isDaemon = true; start() }

    private fun loop() {
        while (true) {
            try {
                val job = client.claimClaudeLogin(runnerId)
                if (job != null) {
                    println("Claude 로그인 시작: ${job.name}")
                    val state = runCatching {
                        ClaudeAccounts.login(job.name, { client.claudeLoginUrl(job.id, it) }, { client.claudeLoginCode(job.id) })
                    }.getOrElse { ClaudeAccountReport(job.name, false) }
                    client.claudeLoginResult(job.id, state.loggedIn, state.email, state.plan,
                        if (state.loggedIn) null else "로그인하지 못했습니다(코드가 틀렸거나 시간이 지났습니다)")
                    println("Claude 로그인 종료: ${job.name} (${if (state.loggedIn) state.plan ?: "구독 없음" else "실패"})")
                    runCatching { client.reportClaudeAccounts(runnerId, ClaudeAccounts.refresh()) }
                }
            } catch (e: Exception) {
            }
            Thread.sleep(3000)
        }
    }
}
