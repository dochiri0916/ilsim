package com.dochiri.ilsim.runner

import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 같은 계열의 새 버전은 바로 쓰고, 낮은 버전·다른 계열·Fable 은 받지 않는다. */
class ModelDiscoveryTest {

    @AfterTest
    fun tearDown() = ClaudeModels.reset()

    @Test
    fun `별칭 확인 출력에서 버전이 붙은 모델 ID 를 만든다`() {
        assertEquals("claude-sonnet-5-6", ModelDiscovery.parse("Set model to `Sonnet 5.6` for this session only"))
        assertEquals("claude-opus-6", ModelDiscovery.parse("Set model to `Opus 6` for this session only"))
        assertNull(ModelDiscovery.parse("Usage: /model <name>"))
    }

    @Test
    fun `같은 계열의 더 높은 버전만 받아 검토 모델도 따라 올라간다`() {
        assertTrue(ClaudeModels.upgrade("sonnet", "claude-sonnet-5-6"))
        assertEquals("claude-sonnet-5-6", ClaudeModels.SONNET)
        assertEquals("claude-sonnet-5-6", ClaudeModels.REVIEW)
        assertFalse(ClaudeModels.upgrade("sonnet", "claude-sonnet-5-5"))
        assertFalse(ClaudeModels.upgrade("sonnet", "claude-opus-6"))
        assertFalse(ClaudeModels.upgrade("opus", "claude-fable-6"))
        assertEquals("claude-opus-5-5", ClaudeModels.OPUS)
    }

    @Test
    fun `Fable 모델로는 Claude 를 실행하지 않는다`() {
        val tmp = Path.of(System.getProperty("java.io.tmpdir"))
        assertFailsWith<IllegalStateException> { Shell.process(tmp, listOf("claude", "-p", "--model", "claude-fable-5-1")) }
        Shell.process(tmp, listOf("claude", "-p", "--model", ClaudeModels.SONNET))
    }
}
