package com.dochiri.ilsim.task.domain

enum class ModelTier(val label: String, val modelId: String) {
    HAIKU("haiku", "claude-haiku-5-5"),
    SONNET("sonnet", "claude-sonnet-5-5"),
    OPUS("opus", "claude-opus-5-5"),
    ;

    fun allows(effort: String): Boolean = !(this != HAIKU && effort == ModelOptions.MAX_EFFORT)

    companion object {
        fun of(model: String?): ModelTier? = entries.firstOrNull { model?.startsWith("claude-${it.label}-") == true }
    }
}
