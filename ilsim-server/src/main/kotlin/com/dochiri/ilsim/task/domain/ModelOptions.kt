package com.dochiri.ilsim.task.domain

data class ModelOption(val tier: ModelTier, val effort: String) {
    val key: String get() = "${tier.modelId}/$effort"
}

object ModelOptions {
    const val MAX_EFFORT = "max"
    val efforts = listOf("low", "medium", "high", "xhigh", MAX_EFFORT)
    val all = ModelTier.entries.flatMap { tier -> efforts.filter(tier::allows).map { ModelOption(tier, it) } }
}
