package com.dochiri.ilsim.runner

import java.nio.file.Files
import java.nio.file.Path

object ClaudeModels {
    @Volatile var HAIKU = "claude-haiku-5-5"
        private set
    @Volatile var SONNET = "claude-sonnet-5-5"
        private set
    @Volatile var OPUS = "claude-opus-5-5"
        private set
    const val EFFORT = "medium"
    val REVIEW: String get() = SONNET

    val LOCKED = setOf("fable")

    fun allows(model: String, effort: String): Boolean = !(effort == "max" && (model.contains("sonnet") || model.contains("opus")))

    fun clampEffort(model: String, effort: String): String = if (allows(model, effort)) effort else "xhigh"

    fun isLocked(model: String?): Boolean = LOCKED.any { model?.lowercase()?.contains(it) == true }

    fun all(): Map<String, String> = mapOf("haiku" to HAIKU, "sonnet" to SONNET, "opus" to OPUS)

    @Synchronized
    fun upgrade(label: String, id: String): Boolean {
        if (isLocked(id) || !Regex("^claude-$label-\\d+(-\\d+)*$").matches(id)) return false
        val current = all()[label] ?: return false
        if (compare(id, current) <= 0) return false
        when (label) { "haiku" -> HAIKU = id; "sonnet" -> SONNET = id; "opus" -> OPUS = id }
        return true
    }

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

    internal fun parse(text: String): String? {
        val m = Regex("""Set model to `?([A-Za-z]+) (\d+(?:\.\d+)*)`?""").find(text) ?: return null
        return "claude-${m.groupValues[1].lowercase()}-${m.groupValues[2].replace('.', '-')}"
    }

    fun refresh(): Map<String, String> = listOf("haiku", "sonnet", "opus").mapNotNull { alias ->
        val id = runCatching { resolve(alias) }.getOrNull() ?: return@mapNotNull null
        if (ClaudeModels.upgrade(alias, id)) println("새 모델 도입: $alias → $id")
        alias to id
    }.toMap()
}
