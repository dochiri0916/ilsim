package com.dochiri.ilsim.runner.jev

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.gudcks0305.jev.JevException
import io.github.gudcks0305.jev.spi.JevTransport
import io.github.gudcks0305.jev.typesafe.TypeSafeJevClient
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RequirementCheckTest {
    private val mapper = ObjectMapper()

    private class Transport(
        val mapper: ObjectMapper,
        val delayMillis: Long = 0,
        val fail: (JsonNode) -> Boolean = { false },
        val probability: (Requirement) -> Double = { 0.95 },
    ) : JevTransport {
        val requests = java.util.Collections.synchronizedList(mutableListOf<JsonNode>())
        val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()
        private val executor = Executors.newCachedThreadPool()

        override fun post(uri: URI, headers: Map<String, String>, body: JsonNode): CompletableFuture<JsonNode> {
            requests.add(body)
            if (fail(body)) return CompletableFuture.failedFuture(JevException(JevException.Kind.SERVER, "boom", 500))
            val running = inFlight.incrementAndGet()
            maxInFlight.accumulateAndGet(running) { a, b -> maxOf(a, b) }
            return CompletableFuture.supplyAsync({
                if (delayMillis > 0) TimeUnit.MILLISECONDS.sleep(delayMillis)
                val requirements = requirementsOf(body)
                val answers = mapper.createObjectNode()
                body.path("questions").fieldNames().forEach { id ->
                    val index = id.drop(1).toInt()
                    val p = if (id.startsWith("m")) probability(requirements[index]) else 0.95
                    answers.putObject(id).put("type", "noul").put("noul", p)
                }
                inFlight.decrementAndGet()
                mapper.createObjectNode().put("model", "jev-latest").set<JsonNode>("answers", answers)
            }, executor)
        }

        override fun close() { executor.shutdownNow() }
    }

    private fun jev(transport: Transport, cache: Path? = null) =
        Jev(TypeSafeJevClient.builder().apiKey("k").transport(transport).build(), cache)

    private fun requirements(count: Int, text: (Int) -> String = { "요구사항 본문 $it" }) =
        (1..count).map { Requirement("REQ-$it", text(it)) }

    companion object {
        fun requirementsOf(body: JsonNode): List<Requirement> =
            body.path("state").path("requirements").map { Requirement(it.path("id").asText(), it.path("text").asText()) }

        fun diffOf(body: JsonNode): String = body.path("state").path("diff").asText()

        fun requestWeight(body: JsonNode): Int =
            Guidelines.weight(diffOf(body)) + requirementsOf(body).sumOf { Guidelines.weight(it.id) + Guidelines.weight(it.text) }
    }

    @Test
    fun `TC-1 한도 안의 diff는 원문 그대로 요청 하나로 묻는다`() {
        val transport = Transport(mapper)
        val diff = "+ fun slug() = 1\n".repeat(10)
        val result = assertNotNull(RequirementCheck.judge(jev(transport), requirements(2), diff))
        assertEquals(1, transport.requests.size)
        assertEquals(diff, diffOf(transport.requests.single()))
        assertEquals(false, result.truncated)
    }

    @Test
    fun `TC-2 분량이 정확히 12,000인 diff는 자르지 않는다`() {
        val transport = Transport(mapper)
        val diff = "가".repeat(Guidelines.MAX_DIFF_WEIGHT)
        val result = assertNotNull(RequirementCheck.judge(jev(transport), requirements(1), diff))
        assertEquals(false, result.truncated)
        assertEquals(diff, diffOf(transport.requests.single()))
    }

    @Test
    fun `TC-3 분량이 12,001인 diff는 Guidelines cut 기준으로 자른다`() {
        val transport = Transport(mapper)
        val diff = "가".repeat(Guidelines.MAX_DIFF_WEIGHT + 1)
        val result = assertNotNull(RequirementCheck.judge(jev(transport), requirements(1), diff))
        assertTrue(result.truncated)
        assertEquals(Guidelines.cut(diff, Guidelines.MAX_DIFF_WEIGHT), diffOf(transport.requests.single()))
    }

    @Test
    fun `TC-4 기존 60,000자 한도 안이라도 분량이 12,000을 넘는 ASCII diff는 자른다`() {
        val transport = Transport(mapper)
        val diff = "a".repeat(30_000)
        val result = assertNotNull(RequirementCheck.judge(jev(transport), requirements(1), diff))
        assertTrue(result.truncated)
        assertEquals(Guidelines.cut(diff, Guidelines.MAX_DIFF_WEIGHT), diffOf(transport.requests.single()))
    }

    @Test
    fun `TC-5 짧은 요구사항 10개는 요청 하나에 모두 넣는다`() {
        val transport = Transport(mapper)
        assertNotNull(RequirementCheck.judge(jev(transport), requirements(10), "+ x\n"))
        assertEquals(1, transport.requests.size)
        assertEquals(10, requirementsOf(transport.requests.single()).size)
    }

    @Test
    fun `TC-6 짧은 요구사항 11개는 앞에서부터 10개와 1개로 나눈다`() {
        val transport = Transport(mapper)
        val all = requirements(11)
        assertNotNull(RequirementCheck.judge(jev(transport), all, "+ x\n"))
        val batches = transport.requests.map { requirementsOf(it).map { r -> r.id } }.sortedBy { it.first().removePrefix("REQ-").toInt() }
        assertEquals(listOf(all.take(10).map { it.id }, listOf("REQ-11")), batches)
    }

    @Test
    fun `TC-7 TC-8 요청마다 분량이 한도 이하이고 모든 요구사항이 순서대로 한 번씩 든다`() {
        val transport = Transport(mapper)
        val all = requirements(4) { "가".repeat(4000) }
        assertNotNull(RequirementCheck.judge(jev(transport), all, "가".repeat(Guidelines.MAX_DIFF_WEIGHT)))
        assertTrue(transport.requests.size >= 2, "한 요청에 넣으면 입력 한도를 넘는다")
        assertTrue(transport.requests.all { requestWeight(it) <= Guidelines.MAX_STATE_WEIGHT })
        val ids = transport.requests.map { requirementsOf(it).map { r -> r.id } }.sortedBy { it.first().removePrefix("REQ-").toInt() }.flatten()
        assertEquals(all.map { it.id }, ids)
    }

    @Test
    fun `TC-8 11개로 나눈 요청에도 모든 요구사항이 정확히 한 번씩 든다`() {
        val transport = Transport(mapper)
        val all = requirements(11)
        assertNotNull(RequirementCheck.judge(jev(transport), all, "+ x\n"))
        val ids = transport.requests.flatMap { requirementsOf(it).map { r -> r.id } }
        assertEquals(all.map { it.id }.sorted(), ids.sorted())
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `TC-9 혼자서 한도를 넘는 요구사항은 본문을 자르지 않고 혼자 요청 하나로 묻는다`() {
        val transport = Transport(mapper)
        val big = Requirement("REQ-1", "가".repeat(11_000))
        val small = Requirement("REQ-2", "짧은 요구사항")
        assertNotNull(RequirementCheck.judge(jev(transport), listOf(big, small), "가".repeat(Guidelines.MAX_DIFF_WEIGHT + 1)))
        val sent = transport.requests.map { requirementsOf(it) }
        assertEquals(2, sent.size)
        assertTrue(sent.contains(listOf(big)), "큰 요구사항은 본문 그대로 혼자 든다")
        assertTrue(sent.contains(listOf(small)))
    }

    @Test
    fun `TC-10 나눈 요청의 충족 확률을 요구사항 ID마다 합친다`() {
        val transport = Transport(mapper, probability = { it.id.removePrefix("REQ-").toInt() / 100.0 })
        val all = requirements(11)
        val result = assertNotNull(RequirementCheck.judge(jev(transport), all, "+ x\n"))
        assertEquals(2, transport.requests.size)
        assertEquals(all.associate { it.id to it.id.removePrefix("REQ-").toInt() / 100.0 }, result.probabilities)
    }

    @Test
    fun `TC-11 요청마다 질문 ID를 새로 매기고 그 요청의 요구사항만 가리킨다`() {
        val transport = Transport(mapper)
        assertNotNull(RequirementCheck.judge(jev(transport), requirements(11), "+ x\n"))
        assertEquals(2, transport.requests.size)
        transport.requests.forEach { body ->
            val ids = requirementsOf(body).map { it.id }
            val expected = ids.indices.flatMap { listOf("m$it", "d$it") }.toSet()
            assertEquals(expected, body.path("questions").fieldNames().asSequence().toSet())
            val mentioned = Regex("""REQ-\d+""").findAll(body.path("questions").toString()).map { it.value }.toSet()
            assertEquals(ids.toSet(), mentioned)
        }
    }

    @Test
    fun `TC-12 나눈 요청 중 하나라도 실패하면 결과를 쓰지 않는다`() {
        val transport = Transport(mapper, fail = { body -> requirementsOf(body).any { it.id == "REQ-11" } })
        assertNull(RequirementCheck.judge(jev(transport), requirements(11), "+ x\n"))
    }

    @Test
    fun `TC-13 같은 입력이면 같은 요청을 만들어 캐시를 다시 쓴다`() {
        val dir = Files.createTempDirectory("ilsim-req-cache")
        try {
            val transport = Transport(mapper, probability = { it.id.removePrefix("REQ-").toInt() / 100.0 })
            val client = jev(transport, dir)
            val all = requirements(11)
            val first = RequirementCheck.judge(client, all, "+ x\n")
            val sent = transport.requests.size
            val second = RequirementCheck.judge(client, all, "+ x\n")
            assertEquals(2, sent)
            assertEquals(sent, transport.requests.size)
            assertEquals(first, second)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `TC-14 나눈 요청은 4개까지 병렬로 묻고 결과는 순차 처리와 같다`() {
        val all = requirements(55)
        val probability: (Requirement) -> Double = { it.id.removePrefix("REQ-").toInt() / 100.0 }
        val slow = Transport(mapper, delayMillis = 150, probability = probability)
        val result = assertNotNull(RequirementCheck.judge(jev(slow), all, "+ x\n"))
        assertTrue(slow.requests.size >= 5)
        assertTrue(slow.maxInFlight.get() in 2..Guidelines.PARALLEL, "동시 요청 ${slow.maxInFlight.get()}개")
        assertEquals(all.map { it.id }, result.probabilities.keys.toList())
        assertEquals(all.associate { it.id to probability(it) }, result.probabilities)
    }

    @Test
    fun `TC-15 잘리지 않은 diff는 0점2 이하를 누락으로 0점8 미만을 불확실로 본다`() {
        val scores = mapOf("REQ-1" to 0.1, "REQ-2" to 0.5, "REQ-3" to 0.9)
        val transport = Transport(mapper, probability = { scores.getValue(it.id) })
        val all = requirements(3)
        val result = assertNotNull(RequirementCheck.judge(jev(transport), all, "+ x\n"))
        assertEquals(false, result.truncated)
        assertEquals(listOf("REQ-1"), result.missing.map { it.id })
        assertEquals(listOf("REQ-2"), result.uncertain.map { it.id })
    }

    @Test
    fun `TC-16 확률 0점2는 누락이고 0점8은 충족이다`() {
        val scores = mapOf("REQ-1" to RequirementCheck.MISSING, "REQ-2" to RequirementCheck.MET)
        val transport = Transport(mapper, probability = { scores.getValue(it.id) })
        val result = assertNotNull(RequirementCheck.judge(jev(transport), requirements(2), "+ x\n"))
        assertEquals(listOf("REQ-1"), result.missing.map { it.id })
        assertTrue(result.uncertain.isEmpty())
    }

    @Test
    fun `TC-17 잘린 diff는 누락 판정 없이 0점8 미만을 모두 불확실로 둔다`() {
        val scores = mapOf("REQ-1" to 0.1, "REQ-2" to 0.5, "REQ-3" to 0.9)
        val transport = Transport(mapper, probability = { scores.getValue(it.id) })
        val result = assertNotNull(RequirementCheck.judge(jev(transport), requirements(3), "a".repeat(30_000)))
        assertTrue(result.truncated)
        assertTrue(result.missing.isEmpty())
        assertEquals(listOf("REQ-1", "REQ-2"), result.uncertain.map { it.id })
    }

    @Test
    fun `TC-19 JEV가 꺼졌거나 요구사항이 없으면 요청 없이 null이다`() {
        assertNull(RequirementCheck.judge(Jev(null), requirements(2), "+ x\n"))
        val transport = Transport(mapper)
        assertNull(RequirementCheck.judge(jev(transport), emptyList(), "+ x\n"))
        assertEquals(0, transport.requests.size)
    }

    @Test
    fun `TC-20 빈 diff는 그대로 요청 하나로 묻는다`() {
        val transport = Transport(mapper)
        val result = assertNotNull(RequirementCheck.judge(jev(transport), requirements(1), ""))
        assertEquals(1, transport.requests.size)
        assertEquals("", diffOf(transport.requests.single()))
        assertEquals(false, result.truncated)
    }

    @Test
    fun `TC-21 JevRequirementEval이 쓰는 기준과 질문은 그대로다`() {
        assertEquals(0.8, RequirementCheck.MET)
        assertEquals(0.2, RequirementCheck.MISSING)
        val all = requirements(2)
        assertEquals(RequirementCheck.questions(all).keys, JevRequirementEval.questions(all).keys)
        assertEquals(setOf("m0", "d0", "m1", "d1"), RequirementCheck.questions(all).keys)
    }

    @Test
    fun `TC-22 설계 문서에 요구사항 판정도 같은 입력 한도를 따른다고 적는다`() {
        val design = Path.of("").toAbsolutePath().parent.resolve("docs/mcp-design.md").readText()
        val limit = design.lines().first { "JEV 입력 한도" in it }
        assertContains(limit, "요구사항 판정")
    }
}
