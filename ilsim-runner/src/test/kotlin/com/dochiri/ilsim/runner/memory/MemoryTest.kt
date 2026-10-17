package com.dochiri.ilsim.runner.memory

import com.dochiri.ilsim.runner.ConfigStore
import com.dochiri.ilsim.runner.profile.StackDetector
import com.dochiri.ilsim.runner.convention.FactScanner
import com.dochiri.ilsim.runner.risk.Detectors
import com.dochiri.ilsim.runner.risk.RiskConfig
import com.dochiri.ilsim.runner.risk.RiskOpinion
import com.dochiri.ilsim.runner.risk.RiskReviewer
import com.dochiri.ilsim.runner.risk.RiskScanner
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MemoryTest {

    private val service = "package com.x\n\n@Service\nclass Svc {\n    private var n = 0\n}\n"

    private fun project(text: String = service): Path {
        val root = Files.createTempDirectory("ilsim-mem")
        root.resolve("src/main/kotlin/com/x/Svc.kt").also { it.parent.createDirectories() }.writeText(text)
        return root
    }

    private fun memory() = ProjectMemory(ConfigStore(Files.createTempDirectory("ilsim-home").resolve(".ilsim/runner.json")))

    @Test
    fun `검토 의견은 후보로 등록되고 승인해야 활성이 되며 UNSURE 는 등록하지 않는다`() {
        val root = project()
        val findings = RiskScanner.scan(root, StackDetector.detect(root))!!
        val fp = RiskReviewer.fingerprint(root, findings.single())
        val m = memory()

        assertEquals(0, m.collect("p", root, findings, mapOf(fp to RiskOpinion("UNSURE", "모름", reviewer = "sonnet")), "abc1234"))
        assertEquals(1, m.collect("p", root, findings, mapOf(fp to RiskOpinion("FALSE", "단일 스레드", reviewer = "sonnet")), "abc1234"))
        assertEquals(0, m.collect("p", root, findings, mapOf(fp to RiskOpinion("FALSE", "단일 스레드", reviewer = "sonnet")), "abc1234"), "같은 지문은 중복 등록하지 않는다")

        val entry = m.load("p").single()
        assertEquals("CANDIDATE", entry.status)
        assertEquals("abc1234", entry.commit)
        assertTrue(m.suppressed("p").isEmpty(), "승인 전에는 숨기지 않는다")

        m.approve("p", m.pendingIds("p"))
        assertEquals(setOf(fp), m.suppressed("p"))
        assertTrue(memory().load("q").isEmpty(), "다른 프로젝트와 섞이지 않는다")
        assertFailsWith<IllegalArgumentException> { m.approve("p", listOf("nope")) }
    }

    @Test
    fun `코드가 바뀌면 근거가 사라진 메모리는 폐기하고 거절도 폐기로 남긴다`() {
        val root = project()
        val findings = RiskScanner.scan(root, StackDetector.detect(root))!!
        val fp = RiskReviewer.fingerprint(root, findings.single())
        val m = memory()
        m.collect("p", root, findings, mapOf(fp to RiskOpinion("TRUE", "공유 상태", "불변으로", "sonnet")), null)
        m.approve("p", m.pendingIds("p"))
        assertEquals(0, m.verify("p", root, findings))

        val changed = project("package com.x\n\n@Service\nclass Svc {\n    private val n = 0\n}\n")
        val after = RiskScanner.scan(changed, StackDetector.detect(changed))!!
        assertEquals(1, m.verify("p", changed, after))
        assertEquals("SUPERSEDED", m.load("p").single().status)
        assertTrue(m.load("p").single().supersededReason!!.contains("코드가 바뀌어"))

        m.collect("p", root, findings, mapOf(fp to RiskOpinion("FALSE", "x", reviewer = "sonnet")), null)
        m.reject("p", m.pendingIds("p"))
        assertTrue(m.suppressed("p").isEmpty())
    }

    @Test
    fun `보호 장치는 같은 모듈과 의존 모듈에서만 인정한다`() {
        fun file(module: String, name: String, body: String) = FactScanner.parse("$module/$name.kt", "kt", false, body.lines())!!.also { it.module = module }
        val handler = """
            package com.x.$$

            @Service
            class H(private val orderRepository: OrderRepository, private val kafkaTemplate: KafkaTemplate<String, String>) {
                fun run() {
                    orderRepository.save(1)
                    kafkaTemplate.send("t", "m")
                }
            }
        """.trimIndent()
        val files = listOf(
            file("shared", "Outbox", "package com.x.shared\n\nclass OutboxRelay\n"),
            file("billing", "H", handler.replace("$$", "billing")),
            file("catalog", "H", handler.replace("$$", "catalog")),
        )
        val deps = mapOf("shared" to emptyList(), "billing" to listOf(":shared"), "catalog" to emptyList())
        val hits = Detectors.detect(files, true, deps).filter { it.ruleId == "DIST-001" }.map { it.file }
        assertEquals(listOf("catalog/H.kt"), hits, "billing 은 shared 의 outbox 를 쓰므로 제외")
        assertEquals(0, Detectors.detect(files, true, emptyMap()).count { it.ruleId == "DIST-001" }, "모듈 정보가 없으면 프로젝트 전체 기준")
    }

    private fun noisyProject(n: Int): Path {
        val root = Files.createTempDirectory("ilsim-noise")
        (1..n).forEach { root.resolve("src/main/kotlin/com/x/S$it.kt").also { f -> f.parent.createDirectories() }.writeText(service.replace("Svc", "S$it")) }
        return root
    }

    @Test
    fun `직접 제외한 오탐이 쌓이면 AI 검토 도입을 제안하고 승인한 규칙에는 다시 제안하지 않는다`() {
        val root = noisyProject(4)
        val profile = StackDetector.detect(root)
        val m = memory()
        val store = RiskScanner.Store(ConfigStore(Files.createTempDirectory("ilsim-home").resolve(".ilsim/runner.json")))
        fun current() = RiskScanner.scan(root, profile)!!.filter { RiskReviewer.fingerprint(root, it) !in m.suppressed("p") }

        assertEquals(2, m.dismiss("p", root, current(), null) { it.file.endsWith("S1.kt") || it.file.endsWith("S2.kt") })
        assertTrue(NoiseDetector.proposals(RiskConfig(), current(), m.load("p")).isEmpty(), "2건은 제안하기엔 이르다")

        assertEquals(1, m.dismiss("p", root, current(), null) { it.file.endsWith("S3.kt") })
        val proposals = NoiseDetector.proposals(RiskConfig(), current(), m.load("p"))
        assertEquals(listOf("CON-001"), proposals.map { it.ruleId })
        assertEquals(3, proposals.single().dismissed)
        assertEquals(4, proposals.single().total)
        assertTrue(NoiseDetector.message("p", proposals.single()).contains("risk-ai p approve CON-001"))

        assertTrue(NoiseDetector.proposals(RiskConfig(aiReview = setOf("CON-001")), current(), m.load("p")).isEmpty())
        assertEquals(0, m.dismiss("p", root, current(), null) { false })
        assertEquals(setOf("CON-001"), store.setAi("p", listOf("CON-001"), true).aiReview)
        assertTrue(store.setAi("p", listOf("CON-001"), false).aiReview.isEmpty())
        assertFailsWith<IllegalArgumentException> { store.setAi("p", listOf("NOPE"), true) }
    }

    @Test
    fun `AI 가 오탐으로 본 의견만으로는 제안 근거가 되지 않는다`() {
        val root = noisyProject(4)
        val findings = RiskScanner.scan(root, StackDetector.detect(root))!!
        val m = memory()
        val opinions = findings.associate { RiskReviewer.fingerprint(root, it) to RiskOpinion("FALSE", "x", reviewer = "sonnet") }
        m.collect("p", root, findings, opinions, null)
        m.approve("p", m.pendingIds("p"))
        assertTrue(NoiseDetector.proposals(RiskConfig(), emptyList(), m.load("p")).isEmpty())
    }
}
