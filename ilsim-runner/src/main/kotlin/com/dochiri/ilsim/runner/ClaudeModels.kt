package com.dochiri.ilsim.runner

object ClaudeModels {
    const val HAIKU = "claude-haiku-5-5"
    const val SONNET = "claude-sonnet-5-5"
    const val OPUS = "claude-opus-5-5"
    const val EFFORT = "medium"
    val REVIEW: String get() = SONNET

    val LOCKED = setOf("fable")

    fun allows(model: String, effort: String): Boolean = !(effort == "max" && (model.contains("sonnet") || model.contains("opus")))

    fun clampEffort(model: String, effort: String): String = if (allows(model, effort)) effort else "xhigh"

    fun isLocked(model: String?): Boolean = LOCKED.any { model?.lowercase()?.contains(it) == true }
}
