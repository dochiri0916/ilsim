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

/** 서버에 등록한 일심 공통 Claude 설정. 계정 설정 디렉터리에 `CLAUDE.md`·`settings.json` 으로 쓴다. */
@Serializable
data class ClaudeEnvironmentReport(val claudeMd: String = "", val settingsJson: String = "{}", val version: String = "")

/** `claude auth status --json` 으로 읽은 계정 상태. [plan]이 없으면 구독이 없는 계정이다. */
@Serializable
data class ClaudeAccountReport(val name: String, val loggedIn: Boolean, val email: String? = null, val plan: String? = null) {
    val usable: Boolean get() = loggedIn && !plan.isNullOrBlank()
}

/**
 * 일심에 등록한 Claude 계정. 계정마다 `~/.ilsim/claude-accounts/<이름>` 을 `CLAUDE_CONFIG_DIR` 로 써서 로그인·세션 기록·설정이 섞이지 않는다
 * (macOS 키체인 항목도 디렉터리별로 따로 생긴다). 등록한 계정이 없으면 기존처럼 기본 로그인(`~/.claude`)을 쓴다.
 */
object ClaudeAccounts {
    var root: Path = Path.of(System.getProperty("user.home"), ".ilsim", "claude-accounts")
    private val states = ConcurrentHashMap<String, ClaudeAccountReport>()
    @Volatile var environment: ClaudeEnvironmentReport? = null
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    /** 계정 명령은 프로젝트 파일과 무관한 곳에서 실행한다. */
    private val tmp: Path get() = Path.of(System.getProperty("java.io.tmpdir"))

    fun dir(name: String): Path = root.resolve(name)

    fun names(): List<String> = if (!root.isDirectory()) emptyList()
        else Files.list(root).use { s -> s.filter { it.isDirectory() }.map { it.fileName.toString() }.sorted().toList() }

    /** 상태를 다시 읽는다. 모델을 부르지 않는다. */
    fun refresh(): List<ClaudeAccountReport> {
        val now = names().map { name -> ClaudeAccountScope.within(name) { status(name) } }
        states.keys.retainAll(now.map { it.name }.toSet())
        now.forEach { states[it.name] = it }
        return now
    }

    fun status(name: String): ClaudeAccountReport {
        val r = runCatching { Shell.run(tmp, "claude", "auth", "status", "--json", timeoutMinutes = 1) }.getOrNull()
        return parseStatus(name, r?.output.orEmpty())
    }

    internal fun parseStatus(name: String, output: String): ClaudeAccountReport {
        val obj = runCatching { json.parseToJsonElement(output.substring(output.indexOf('{').coerceAtLeast(0))) as JsonObject }.getOrNull()
            ?: return ClaudeAccountReport(name, false)
        fun text(key: String) = obj[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
        return ClaudeAccountReport(name, obj["loggedIn"]?.jsonPrimitive?.booleanOrNull == true, text("email"), text("subscriptionType"))
    }

    fun usable(): List<String> = states.values.filter { it.usable }.map { it.name }.sorted()

    /**
     * 새 세션을 열 때 쓸 계정. 남은 5시간·주간 사용량 중 더 빡빡한 쪽이 가장 여유 있는 계정을 고른다(사용량을 모르면 0으로 본다).
     * 실행 중 한도 초과가 나도 다른 계정으로 넘기지 않는다. 쓸 계정이 없으면 null(기본 로그인).
     */
    fun pick(usage: (String) -> PlanUsageReport? = { PlanUsageWatch.latest(ClaudeAccountScope.provider(it)) }): String? =
        usable().minByOrNull { name -> usage(name)?.let { maxOf(it.fiveHour?.utilization ?: 0.0, it.sevenDay?.utilization ?: 0.0) } ?: 0.0 }

    /** 서버 설정을 계정 디렉터리에 쓴다. 이미 같은 버전이면 건드리지 않는다. */
    fun applyEnvironment(name: String) {
        val env = environment ?: return
        val dir = dir(name)
        if (!dir.exists()) return
        val marker = dir.resolve(".ilsim-environment")
        if (marker.exists() && marker.readText() == env.version) return
        KnowledgeFiles.write(dir.resolve("CLAUDE.md"), env.claudeMd)
        KnowledgeFiles.write(dir.resolve("settings.json"), env.settingsJson)
        marker.writeText(env.version)
    }

    /** 웹에서 지운 계정: 로그아웃(키체인 항목 정리) 후 디렉터리를 지운다. */
    fun remove(name: String) {
        val dir = dir(name)
        if (!dir.exists()) return
        ClaudeAccountScope.within(name) { runCatching { Shell.run(tmp, "claude", "auth", "logout", timeoutMinutes = 1) } }
        dir.toFile().deleteRecursively()
        states.remove(name)
    }

    /**
     * 웹에서 시작한 로그인. `claude auth login` 이 출력한 URL 을 [onUrl] 로 넘기고, 사용자가 웹에 넣은 코드를 [nextCode] 로 받아 입력한다.
     * 브라우저는 이 PC 에서 열지 않는다(휴대폰 등 어디서든 로그인할 수 있게). 끝나면 계정 상태를 돌려준다.
     */
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

    /** 출력에는 터미널 링크 제어 문자가 섞여 있다. */
    internal fun loginUrl(output: String): String? = Regex("""https://[^\s\u0007\u001b"'<>]*oauth/authorize[^\s\u0007\u001b"'<>]*""").find(output)?.value
}

/** 이 스레드에서 쓸 Claude 계정. null 이면 기본 로그인(`~/.claude`). */
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

    /** 계정 디렉터리로 실행하고, 구독 대신 API 요금으로 새지 않도록 API 키 변수는 지운다. */
    fun configure(env: MutableMap<String, String>) {
        val name = current.get() ?: return
        env["CLAUDE_CONFIG_DIR"] = ClaudeAccounts.dir(name).toString()
        env.remove("ANTHROPIC_API_KEY")
        env.remove("ANTHROPIC_AUTH_TOKEN")
        env.remove("CLAUDE_CODE_OAUTH_TOKEN")
    }

    /** 세션 id 에 계정을 붙여 이어 갈 때 같은 계정으로 간다. 붙지 않은 id 는 기본 로그인 세션이다. */
    fun tag(sessionId: String?): String? = sessionId?.let { id -> current.get()?.let { "claude@$it:$id" } ?: id }

    fun accountOf(sessionId: String): String? = Regex("^claude@([a-z0-9][a-z0-9-]*):").find(sessionId)?.groupValues?.get(1)

    fun rawSession(sessionId: String): String = sessionId.replace(Regex("^claude@[a-z0-9][a-z0-9-]*:"), "")
}

/** 웹에서 시작한 로그인을 받아 처리하는 스레드. 로그인은 사람이 브라우저에서 하므로 작업 루프를 막지 않게 따로 돈다. */
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
                // 서버가 잠시 없어도 다시 시도한다.
            }
            Thread.sleep(3000)
        }
    }
}
