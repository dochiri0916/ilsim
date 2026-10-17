package com.dochiri.ilsim.runner.brief

import com.dochiri.ilsim.runner.architecture.ArchRule
import com.dochiri.ilsim.runner.architecture.ArchitectureModel
import com.dochiri.ilsim.runner.architecture.ArchitectureReviewer
import com.dochiri.ilsim.runner.architecture.Evidence
import com.dochiri.ilsim.runner.architecture.Layout
import com.dochiri.ilsim.runner.convention.ConventionModel
import com.dochiri.ilsim.runner.convention.ConventionReviewer
import com.dochiri.ilsim.runner.convention.ConventionRule
import com.dochiri.ilsim.runner.risk.RiskConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RulesBriefTest {
    private fun rule(id: String, desc: String, sev: String, approved: Boolean, rejected: Boolean = false) =
        ArchRule(id, "leaf-unit", desc, emptyMap(), sev, Evidence(1, 1), approved = approved, rejected = rejected)

    private val arch = ArchitectureModel(
        "p", 1, Layout("com.x", "FLAT"), emptyList(), emptyList(), emptyList(), emptyList(),
        listOf(
            rule("a", "domain 은 다른 단위에 의존하지 않는다 (관측: 40개 파일 모두 해당)", "REVIEW", true),
            rule("b", "service 는 web 에 의존하지 않는다", "BLOCK", true),
            rule("c", "승인 전 후보", "BLOCK", false),
            rule("d", "거절한 규칙", "BLOCK", false, rejected = true),
        ),
        emptyList(),
    )
    private val conv = ConventionModel("p", 1, 0, emptyMap(), listOf(ConventionRule("i", "indent-style", "들여쓰기는 공백 4칸이다 (관측: 10/10)", approved = true)))

    @Test
    fun `승인된 규칙만 BLOCK 먼저 근거 수치 없이 요약한다`() {
        val brief = RulesBrief.build(arch, conv)!!
        assertTrue(brief.indexOf("service 는 web") < brief.indexOf("domain 은 다른"), "BLOCK 규칙이 먼저")
        assertTrue(brief.contains("들여쓰기는 공백 4칸이다") && !brief.contains("관측:"))
        assertTrue(!brief.contains("승인 전 후보") && !brief.contains("거절한 규칙"))
        assertTrue(brief.contains("TX-003") && brief.contains("CON-002"), "기본 BLOCK 위험 규칙")
    }

    @Test
    fun `알릴 규칙이 하나도 없으면 null 이다`() {
        val off = RiskConfig(overrides = mapOf("TX-003" to "OFF", "CON-002" to "OFF", "CON-003" to "OFF"))
        assertNull(RulesBrief.build(null, null, off))
    }

    @Test
    fun `검토 프롬프트는 설계의 좋고 나쁨을 판단하지 않게 하고 KEEP 과 SUSPECT 만 쓴다`() {
        val prompts = listOf(
            ArchitectureReviewer.prompt(arch),
            ConventionReviewer.prompt(conv),
        )
        for (p in prompts) {
            assertTrue(p.contains("좋은지 나쁜지는 판단하지 마라"))
            assertTrue(p.contains("KEEP|SUSPECT") && !p.contains("DROP"))
            assertTrue(!p.contains("있어야 할 규칙") && !p.contains("좋을 컨벤션"), "새 규칙을 제안하게 하지 않는다")
        }
        assertEquals(2, prompts.size)
    }
}
