package com.dochiri.ilsim.task

import com.dochiri.ilsim.task.domain.CostRoutingPolicy
import com.dochiri.ilsim.task.domain.ModelOption
import com.dochiri.ilsim.task.domain.ModelTier
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EffortLimitTest {
    @Test
    fun `Sonnet과 Opus 후보에는 max가 없고 Haiku는 그대로다`() {
        assertThat(CostRoutingPolicy.options).doesNotContain(ModelOption(ModelTier.SONNET, "max"), ModelOption(ModelTier.OPUS, "max"))
        assertThat(CostRoutingPolicy.options).contains(ModelOption(ModelTier.SONNET, "xhigh"), ModelOption(ModelTier.OPUS, "xhigh"), ModelOption(ModelTier.HAIKU, "max"))
        assertThat(ModelTier.SONNET.allows("max")).isFalse()
        assertThat(ModelTier.OPUS.allows("max")).isFalse()
        assertThat(ModelTier.HAIKU.allows("max")).isTrue()
    }
}
