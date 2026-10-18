package com.dochiri.ilsim.runner

import java.nio.file.Files
import java.nio.file.Path

/**
 * 사용하는 Claude 모델과 추론 강도. 실행에는 별칭이 아니라 버전이 붙은 ID 를 보낸다. 같은 계열의 새 버전(예: Sonnet 5.5 → 5.6)이 나오면
 * [ModelDiscovery] 가 토큰 없이 확인해 그쪽으로 올린다. 검토·점검처럼 Runner 가 직접 한 번 부르는 호출은 [REVIEW] 모델과 [EFFORT] 로 실행한다.
 */
object ClaudeModels {
    @Volatile var HAIKU = "claude-haiku-5-5"
        private set
    @Volatile var SONNET = "claude-sonnet-5-5"
        private set
    @Volatile var OPUS = "claude-opus-5-5"
        private set
    const val EFFORT = "medium"
    val REVIEW: String get() = SONNET

    /** 쓰지 않는 계열. 어떤 경로로도 실행하지 않는다. */
    val LOCKED = setOf("fable")

    fun isLocked(model: String?): Boolean = LOCKED.any { model?.lowercase()?.contains(it) == true }

    fun all(): Map<String, String> = mapOf("haiku" to HAIKU, "sonnet" to SONNET, "opus" to OPUS)

    /** 같은 계열의 더 높은 버전이면 올리고 true. 잠긴 계열이나 다른 계열, 낮은 버전은 무시한다. */
    @Synchronized
    fun upgrade(label: String, id: String): Boolean {
        if (isLocked(id) || !Regex("^claude-$label-\\d+(-\\d+)*$").matches(id)) return false
        val current = all()[label] ?: return false
        if (compare(id, current) <= 0) return false
        when (label) { "haiku" -> HAIKU = id; "sonnet" -> SONNET = id; "opus" -> OPUS = id }
        return true
    }

    /** 테스트용: 기본 버전으로 되돌린다. */
    internal fun reset() { HAIKU = "claude-haiku-5-5"; SONNET = "claude-sonnet-5-5"; OPUS = "claude-opus-5-5" }

    private fun compare(a: String, b: String): Int {
        fun version(id: String) = id.split('-').mapNotNull { it.toIntOrNull() }
        val x = version(a)
        val y = version(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = (x.getOrNull(i) ?: 0) - (y.getOrNull(i) ?: 0)
            if (d != 0) return d
        }
        return 0
    }
}

/**
 * Claude Code 의 `/model <별칭>` 으로 별칭이 가리키는 최신 모델을 확인한다. 로컬 명령이라 모델을 부르지 않고 비용·토큰이 0 이다
 * (로그인 없는 빈 설정 디렉터리에서 실행해 기본 로그인의 설정도 건드리지 않는다). 출력 예: "Set model to `Sonnet 5.5` for this session only".
 */
object ModelDiscovery {
    private val dir: Path = Path.of(System.getProperty("user.home"), ".ilsim", "model-probe")

    fun resolve(alias: String): String? {
        Files.createDirectories(dir)
        val builder = Shell.process(dir, listOf("claude", "-p", "/model $alias", "--output-format", "json"))
        builder.environment()["CLAUDE_CONFIG_DIR"] = dir.toString()
        builder.environment().remove("ANTHROPIC_API_KEY")
        val p = builder.start()
        p.outputStream.close()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(1, java.util.concurrent.TimeUnit.MINUTES)) { p.destroyForcibly(); return null }
        return parse(ClaudeCli.parse(out).text)
    }

    /** "Sonnet 5.6" → "claude-sonnet-5-6". 모르는 형식이면 null. */
    internal fun parse(text: String): String? {
        val m = Regex("""Set model to `?([A-Za-z]+) (\d+(?:\.\d+)*)`?""").find(text) ?: return null
        return "claude-${m.groupValues[1].lowercase()}-${m.groupValues[2].replace('.', '-')}"
    }

    /** 세 등급을 확인해 새 버전이면 올리고, 확인한 ID 를 돌려준다(서버 보고용). */
    fun refresh(): Map<String, String> = listOf("haiku", "sonnet", "opus").mapNotNull { alias ->
        val id = runCatching { resolve(alias) }.getOrNull() ?: return@mapNotNull null
        if (ClaudeModels.upgrade(alias, id)) println("새 모델 도입: $alias → $id")
        alias to id
    }.toMap()
}
