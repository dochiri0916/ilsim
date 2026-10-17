package com.dochiri.ilsim.task.domain

/**
 * Agent 가 쓸 수 있는 Claude 모델 등급. 라우팅은 등급만 고르고, 실제 실행에는 5.5 세대의 모델 ID 로 고정해 보낸다
 * (별칭은 최신 모델로 바뀔 수 있어 쓰지 않는다).
 */
enum class ModelTier(val label: String, val modelId: String) {
    HAIKU("haiku", "claude-haiku-5-5"),
    SONNET("sonnet", "claude-sonnet-5-5"),
    OPUS("opus", "claude-opus-5-5"),
    ;

    companion object {
        fun parse(raw: String?, default: ModelTier): ModelTier =
            entries.firstOrNull { it.label == raw?.trim()?.lowercase() } ?: default
    }
}

/** 추론 강도. 기본은 medium. */
object ReasoningEffort {
    private val ALLOWED = setOf("low", "medium", "high", "xhigh", "max")
    const val DEFAULT = "medium"

    fun parse(raw: String?): String = raw?.trim()?.lowercase()?.takeIf { it in ALLOWED } ?: DEFAULT
}
