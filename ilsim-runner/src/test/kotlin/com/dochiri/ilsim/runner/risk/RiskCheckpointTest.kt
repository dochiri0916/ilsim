package com.dochiri.ilsim.runner.risk

import com.dochiri.ilsim.runner.ConfigStore
import com.dochiri.ilsim.runner.architecture.Violation
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.*

class RiskCheckpointTest {
    @Test
    fun `뒤 묶음이 실패해도 앞 묶음 의견은 남기고 재시도는 미완료 후보만 검토한다`() {
        val dir = Files.createTempDirectory("ilsim-risk-checkpoint")
        try {
            dir.resolve("A.kt").writeText((1..80).joinToString("\n") { "val x$it = $it" })
            val targets = (1..11).map { Violation(ruleId = "risk-$it", severity = "REVIEW", file = "A.kt", line = it, message = "위험 $it") }
            val store = RiskReviewer.Store(ConfigStore(dir.resolve("home/runner.json")))
            var calls = 0
            assertFailsWith<IllegalStateException> {
                RiskReviewer.review(dir, targets, emptyMap(), "m/low", false, checkpoint = { store.save("p", it) }) {
                    calls++
                    if (calls == 2) error("두 번째 묶음 장애")
                    """{"findings":[""" + (0..9).joinToString(",") { """{"id":$it,"verdict":"UNSURE","reason":"추가 근거 필요"}""" } + "]}"
                }
            }
            assertEquals(10, store.load("p").size)
            val result = RiskReviewer.review(dir, targets, store.load("p"), "m/low", false) {
                calls++
                assertTrue("risk-11" in it && "risk-1 " !in it)
                """{"findings":[{"id":0,"verdict":"FALSE","reason":"보호 장치 확인"}]}"""
            }
            assertEquals(11, result.size)
            assertEquals(3, calls)
            assertNotEquals(RiskReviewer.fingerprint(dir, targets.first()), RiskReviewer.fingerprint(dir, targets.first().copy(message = "지적 조건 변경")))
        } finally { dir.toFile().deleteRecursively() }
    }
}
