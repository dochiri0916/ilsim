package com.dochiri.ilsim.runner.jev

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.gudcks0305.jev.spi.JevTransport
import io.github.gudcks0305.jev.typesafe.TypeSafeJevClient
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JevRiskEvalTest {
    private val mapper = ObjectMapper()

    private class Transport(val mapper: ObjectMapper, val answer: (String, String) -> Double) : JevTransport {
        override fun post(uri: URI, headers: Map<String, String>, body: JsonNode): CompletableFuture<JsonNode> {
            val code = body.path("state").toString()
            val answers = mapper.createObjectNode()
            body.path("questions").fieldNames().forEach { id -> answers.putObject(id).put("type", "noul").put("noul", answer(id, code)) }
            return CompletableFuture.completedFuture(mapper.createObjectNode().put("model", "jev-latest").set("answers", answers))
        }
        override fun close() {}
    }

    private val service = """
        package com.x;

        import org.springframework.stereotype.Service;

        @Service
        public class RankingService {
            private final java.util.Map<Long, Integer> scores = new java.util.HashMap<>();

            public void add(Long id) {
                scores.merge(id, 1, Integer::sum);
            }
        }
    """.trimIndent()

    private fun case(dir: Path, id: String, label: String, source: String, hidden: Boolean = false) {
        dir.resolve(id).resolve("src/main/java/com/x/RankingService.java").also { it.parent.createDirectories() }.writeText(source)
        dir.resolve(id).resolve("case.json").writeText("""{"rule": "CON-001", "label": "$label", "hidden": $hidden}""")
    }

    @Test
    fun `탐지되지 않은 사례는 제외하고 확정·놓침·오경보·이관을 센다`() {
        val dir = Files.createTempDirectory("ilsim-jev-eval")
        try {
            case(dir, "a-risk", "RISK", service)
            case(dir, "b-safe", "SAFE", service.replace("Integer>", "Integer> ").replace("RankingService {", "RankingService { // safe"))
            case(dir, "c-hidden", "SAFE", service.replace("RankingService {", "RankingService { // hidden"), hidden = true)
            case(dir, "d-none", "RISK", "package com.x;\n\npublic class RankingService {\n}\n")
            val jev = Jev(TypeSafeJevClient.builder().apiKey("k").transport(Transport(mapper) { id, code ->
                when {
                    id.startsWith("e") -> if ("hidden" in code) 0.5 else 0.95
                    "safe" in code -> 0.9
                    else -> 0.95
                }
            }).build())
            val samples = JevRiskEval.load(dir)
            assertNull(samples.single { it.id == "d-none" }.finding)

            val run = JevRiskEval.batched(samples, jev, repeat = 2)
            val summary = JevRiskEval.summarize(samples, run)
            assertEquals(3, summary.detected)
            assertEquals(2, summary.decided)
            assertEquals(1, summary.correct)
            assertEquals(1, summary.falseAlarm)
            assertEquals(1, summary.unsure)
            assertEquals(0, summary.missed)
            assertEquals(0, summary.unstable)

            val report = JevRiskEval.report(samples, listOf(run))
            assertTrue("탐지 안 됨(평가 제외): d-none(CON-001)" in report)
            assertTrue("b-safe | SAFE | TRUE r=0.90 e=0.95 오경보" in report)
            assertTrue(JevRiskEval.toJson(samples, listOf(run)).contains("\"detected\": false"))
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test
    fun `AUROC 는 위험 점수가 안전 점수보다 높은 쌍의 비율이다`() {
        assertEquals(1.0, JevRiskEval.auroc(listOf(0.9, 0.8), listOf(0.1, 0.2)))
        assertEquals(0.5, JevRiskEval.auroc(listOf(0.5), listOf(0.5)))
        assertEquals(0.0, JevRiskEval.auroc(listOf(0.1), listOf(0.9)))
        assertNull(JevRiskEval.auroc(emptyList(), listOf(0.1)))
    }
}
