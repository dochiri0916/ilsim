package com.dochiri.ilsim.task.domain

import java.util.concurrent.ConcurrentHashMap

enum class ModelTier(val label: String, val defaultId: String) {
    HAIKU("haiku", "claude-haiku-5-5"),
    SONNET("sonnet", "claude-sonnet-5-5"),
    OPUS("opus", "claude-opus-5-5"),
    ;

    val modelId: String get() = ModelCatalog.current(this)

    companion object {
        fun parse(raw: String?, default: ModelTier): ModelTier =
            entries.firstOrNull { it.label == raw?.trim()?.lowercase() } ?: default

        fun of(model: String?): ModelTier? = entries.firstOrNull { model?.startsWith("claude-${it.label}-") == true }
    }
}

object ModelCatalog {
    val LOCKED = setOf("fable")
    private val ids = ConcurrentHashMap<ModelTier, String>()

    fun current(tier: ModelTier): String = ids[tier] ?: tier.defaultId

    fun all(): Map<String, String> = ModelTier.entries.associate { it.label to current(it) }

    fun isLocked(model: String?): Boolean = LOCKED.any { model?.lowercase()?.contains(it) == true }

    @Synchronized
    fun upgrade(tier: ModelTier, model: String): String? {
        val id = model.trim().lowercase()
        if (isLocked(id) || ModelTier.of(id) != tier || !Regex("^claude-${tier.label}-\\d+(-\\d+)*$").matches(id)) return null
        val previous = current(tier)
        if (compare(id, previous) <= 0) return null
        ids[tier] = id
        return previous
    }

    fun reset() = ids.clear()

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

object ReasoningEffort {
    private val ALLOWED = setOf("low", "medium", "high", "xhigh", "max")
    const val DEFAULT = "medium"

    fun parse(raw: String?): String = raw?.trim()?.lowercase()?.takeIf { it in ALLOWED } ?: DEFAULT
}
