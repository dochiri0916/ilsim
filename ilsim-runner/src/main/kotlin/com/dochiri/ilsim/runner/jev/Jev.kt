package com.dochiri.ilsim.runner.jev

import io.github.gudcks0305.jev.JevClient
import io.github.gudcks0305.jev.JevException
import io.github.gudcks0305.jev.NoulAnswer
import io.github.gudcks0305.jev.NoulQuestion
import io.github.gudcks0305.jev.typesafe.TypeSafeJevClient
import java.time.Duration
import java.nio.file.Path
import java.util.UUID
import com.dochiri.ilsim.runner.AiUsage
import com.dochiri.ilsim.runner.UsageScope
import com.dochiri.ilsim.runner.KnowledgeFiles
import com.dochiri.ilsim.runner.ReviewCache
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * TypeSafe JEV(결정 모델) 어댑터. 텍스트를 만들지 않고 yes/no 판단의 확률만 돌려주므로, 결과는 판단 근거로만 쓰고 최종 결정은 정책이 한다.
 * 키(환경 변수 TYPESAFE_API_KEY 또는 JEV_API_KEY)가 없거나 호출이 실패하면 null 을 돌려주어 호출한 쪽이 정적·규칙 기반 동작으로 되돌아가게 한다.
 */
/** 서버에 웹으로 등록된 키. 작업을 받을 때마다 갱신하며 메모리에만 둔다. 환경 변수보다 우선한다. */
object JevKey {
    @Volatile var fromServer: String? = null
}

class Jev(private val client: JevClient?, private val cacheDirectory: Path? = null) {
    private val mapper = com.fasterxml.jackson.databind.ObjectMapper()
    private val json = Json { ignoreUnknownKeys = true }
    private var failures = 0
    private var blockedUntil = 0L

    val enabled: Boolean get() = client != null

    /** 질문 id → 확률(0..1). 한 번의 요청에 같은 상태로 여러 질문을 보낸다. 실패하면 null. */
    fun probabilities(state: Any, questions: Map<String, NoulQuestion>): Map<String, Double>? {
        val c = client ?: return null
        if (questions.isEmpty()) return emptyMap()
        val key = ReviewCache.digest(mapper.writeValueAsString(mapOf("policy" to (System.getenv("ILSIM_JEV_POLICY") ?: "jev-v2"), "state" to state,
            "questions" to questions.map { (id, q) -> mapOf("id" to id, "instructions" to q.instructions(), "criteria" to q.criteria()) })))
        val file = cacheDirectory?.resolve("$key.json")
        if (file?.exists() == true) runCatching { json.decodeFromString<Map<String, Double>>(file.readText()) }.getOrNull()
            ?.takeIf { a -> a.keys == questions.keys && a.values.all { it.isFinite() && it in 0.0..1.0 } }?.let { return it }
        if (System.currentTimeMillis() < blockedUntil) return null
        val id = UUID.randomUUID().toString()
        return try {
            val evaluation = c.evaluate(state, *questions.values.toTypedArray())
            val usage = evaluation.usage()
            val rawCost = evaluation.rawResponse().path("usage").path("cost_usd")
            val cost = rawCost.takeIf { it.isNumber }?.asDouble()?.takeIf { it.isFinite() && it >= 0 }
            UsageScope.record(AiUsage(id, "JEV", evaluation.model(), "none", costUsd = cost,
                inputTokens = usage.inputTokens().orElse(0), outputTokens = usage.outputTokens().orElse(0),
                status = if (cost == null) "UNKNOWN" else "CONFIRMED", questions = questions.size))
            val answers = questions.mapValues { (_, q) -> evaluation.answer(q).probability() }
            require(answers.values.all { it.isFinite() && it in 0.0..1.0 }) { "잘못된 JEV 확률" }
            failures = 0
            blockedUntil = 0
            if (file != null) KnowledgeFiles.locked(file.parent) { KnowledgeFiles.write(file, json.encodeToString(answers)) }
            answers
        } catch (e: JevException) {
            System.err.println("JEV 호출 실패: ${e.kind()} (HTTP ${e.statusCode()})")
            failed(id, questions.size)
            null
        } catch (e: RuntimeException) {
            System.err.println("JEV 호출 실패: ${e.javaClass.simpleName}")
            failed(id, questions.size)
            null
        }
    }

    private fun failed(id: String, questions: Int) {
        failures++
        if (failures >= 2) blockedUntil = System.currentTimeMillis() + 60_000
        UsageScope.record(AiUsage(id, "JEV", "jev", "none", status = "UNKNOWN", questions = questions))
    }

    fun probability(state: Any, question: NoulQuestion): Double? = probabilities(state, mapOf(question.id() to question))?.get(question.id())

    companion object {
        private var cachedKey: String? = null
        private var cached: Jev? = null
        @Synchronized
        fun fromEnvironment(): Jev {
            val key = (listOfNotNull(JevKey.fromServer) + listOf("TYPESAFE_API_KEY", "JEV_API_KEY").mapNotNull { System.getenv(it) }).firstOrNull { it.isNotBlank() }?.trim()
            if (cached != null && key == cachedKey) return cached!!
            cachedKey = key
            return Jev(key?.let { TypeSafeJevClient.builder().apiKey(it).timeout(Duration.ofSeconds(15)).maxRetries(1).build() },
                Path.of(System.getProperty("user.home"), ".ilsim", "decisions")).also { cached = it }
        }
    }
}
