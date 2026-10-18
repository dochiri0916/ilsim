package com.dochiri.ilsim.task.domain

import java.util.concurrent.ConcurrentHashMap

/**
 * Agent 가 쓸 수 있는 Claude 모델 등급. 라우팅은 등급만 고르고, 실제 실행에는 [modelId](그 등급의 지금 버전 ID)를 보낸다.
 * 별칭(sonnet 등)은 Claude Code 가 최신 모델로 바꿀 수 있어 실행에는 쓰지 않고, 같은 계열의 새 버전은 [ModelCatalog] 가 받아들인다.
 */
enum class ModelTier(val label: String, val defaultId: String) {
    HAIKU("haiku", "claude-haiku-5-5"),
    SONNET("sonnet", "claude-sonnet-5-5"),
    OPUS("opus", "claude-opus-5-5"),
    ;

    /** 지금 쓰는 버전의 모델 ID. 같은 계열 새 버전이 나오면 그쪽으로 올라간다. */
    val modelId: String get() = ModelCatalog.current(this)

    companion object {
        fun parse(raw: String?, default: ModelTier): ModelTier =
            entries.firstOrNull { it.label == raw?.trim()?.lowercase() } ?: default

        /** 모델 ID 의 계열(버전 무관). `claude-sonnet-5-6` → SONNET. 계열이 다르거나 잠긴 모델이면 null. */
        fun of(model: String?): ModelTier? = entries.firstOrNull { model?.startsWith("claude-${it.label}-") == true }
    }
}

/**
 * 등급별로 지금 쓰는 모델 버전. Runner 가 Claude Code 의 별칭(`/model sonnet` 등, 토큰 0)으로 확인한 최신 ID 를 보고하면
 * 같은 계열의 더 높은 버전만 받아들인다(내려가지 않는다). 새 버전은 같은 등급의 실행 이력·조정을 이어받아 바로 쓴다.
 * Fable 계열은 쓰지 않는다([LOCKED]).
 */
object ModelCatalog {
    val LOCKED = setOf("fable")
    private val ids = ConcurrentHashMap<ModelTier, String>()

    fun current(tier: ModelTier): String = ids[tier] ?: tier.defaultId

    fun all(): Map<String, String> = ModelTier.entries.associate { it.label to current(it) }

    fun isLocked(model: String?): Boolean = LOCKED.any { model?.lowercase()?.contains(it) == true }

    /** 받아들였으면 이전 ID 를 돌려준다. 계열이 다르거나, 잠겼거나, 더 높은 버전이 아니면 null. */
    @Synchronized
    fun upgrade(tier: ModelTier, model: String): String? {
        val id = model.trim().lowercase()
        if (isLocked(id) || ModelTier.of(id) != tier || !Regex("^claude-${tier.label}-\\d+(-\\d+)*$").matches(id)) return null
        val previous = current(tier)
        if (compare(id, previous) <= 0) return null
        ids[tier] = id
        return previous
    }

    /** 테스트용: 기본 버전으로 되돌린다. */
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

/** 추론 강도. 기본은 medium. */
object ReasoningEffort {
    private val ALLOWED = setOf("low", "medium", "high", "xhigh", "max")
    const val DEFAULT = "medium"

    fun parse(raw: String?): String = raw?.trim()?.lowercase()?.takeIf { it in ALLOWED } ?: DEFAULT
}
