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
import kotlin.test.assertTrue

class JevRequirementEvalTest {
    private val mapper = ObjectMapper()

    private class Transport(val mapper: ObjectMapper) : JevTransport {
        override fun post(uri: URI, headers: Map<String, String>, body: JsonNode): CompletableFuture<JsonNode> {
            val diff = body.path("state").path("diff").asText()
            val answers = mapper.createObjectNode()
            body.path("questions").fieldNames().forEach { id ->
                val p = when {
                    id.startsWith("d") -> 0.95
                    id == "m0" -> if ("alpha" in diff) 0.9 else 0.1
                    else -> 0.9
                }
                answers.putObject(id).put("type", "noul").put("noul", p)
            }
            return CompletableFuture.completedFuture(mapper.createObjectNode().put("model", "jev-latest").set("answers", answers))
        }
        override fun close() {}
    }

    private fun scenario(): Path {
        val dir = Files.createTempDirectory("ilsim-req-eval")
        val root = dir.resolve("s").also { it.resolve("hunks").createDirectories(); it.resolve("variants").createDirectories() }
        root.resolve("case.json").writeText("""{"requirements": [{"id": "R1", "text": "알파"}, {"id": "R2", "text": "베타"}],
            "variants": [{"id": "fake-R1", "replace": {"R1": "variants/fake.diff"}, "expect": {"R1": "MISSING"}, "note": "주석뿐"}]}""")
        root.resolve("hunks/R1.diff").writeText("+ alpha\n")
        root.resolve("hunks/R2.diff").writeText("+ beta\n")
        root.resolve("variants/fake.diff").writeText("+ // alpha TODO\n")
        return dir
    }

    @Test
    fun `전체·요구사항별 제거·변형을 만들고 놓침과 잘못된 완료를 센다`() {
        val dir = scenario()
        try {
            val samples = JevRequirementEval.load(dir)
            assertEquals(listOf("s/full", "s/drop-R1", "s/drop-R2", "s/fake-R1"), samples.map { it.id })
            assertEquals("+ beta\n", samples.single { it.id == "s/drop-R1" }.diff)
            assertEquals(mapOf("R1" to "MISSING", "R2" to "MET"), samples.single { it.id == "s/fake-R1" }.expected)

            val runs = JevRequirementEval.jev(samples, Jev(TypeSafeJevClient.builder().apiKey("k").transport(Transport(mapper)).build()))
            val m = JevRequirementEval.summarize(samples, runs.first())
            assertEquals(8, m.judged)
            assertEquals(8, m.decided)
            assertEquals(2, m.missed)
            assertEquals(0, m.falseAlarm)
            assertEquals(3, m.autoComplete)
            assertEquals(2, m.wrongComplete)
            assertTrue("s/fake-R1 | R1✗ R2✓ | R1✓0.90(놓침)" in JevRequirementEval.report(samples, runs))
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test
    fun `Agent 응답은 허용한 판정만 읽는다`() {
        val parsed = JevRequirementEval.parse("""설명 {"requirements":[{"id":"R1","verdict":"MET"},{"id":"R2","verdict":"maybe"}]}""")
        assertEquals(mapOf("R1" to "MET"), parsed)
    }
}
