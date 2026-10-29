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

object JevKey {
    const val RETRY_MILLIS = 30_000L
    @Volatile var fromServer: String? = null
    @Volatile var loader: (() -> String?)? = null
    @Volatile private var lastTry = 0L

    @Synchronized
    fun refresh(now: Long = System.currentTimeMillis()) {
        if (fromServer != null) return
        val load = loader ?: return
        if (lastTry != 0L && now - lastTry < RETRY_MILLIS) return
        lastTry = now
        runCatching(load).getOrNull()?.takeIf { it.isNotBlank() }?.let { fromServer = it }
    }

    @Synchronized
    fun reset() {
        fromServer = null
        loader = null
        lastTry = 0L
    }
}

class Jev(private val client: JevClient?, private val cacheDirectory: Path? = null) {
    private val mapper = com.fasterxml.jackson.databind.ObjectMapper()
    private val json = Json { ignoreUnknownKeys = true }
    private var failures = 0
    private var blockedUntil = 0L
    val calls = java.util.concurrent.atomic.AtomicInteger()
    val reused = java.util.concurrent.atomic.AtomicInteger()

    val enabled: Boolean get() = client != null

    fun probabilities(state: Any, questions: Map<String, NoulQuestion>): Map<String, Double>? {
        val c = client ?: return null
        if (questions.isEmpty()) return emptyMap()
        val key = ReviewCache.digest(mapper.writeValueAsString(mapOf("policy" to (System.getenv("ILSIM_JEV_POLICY") ?: "jev-v2"), "state" to state,
            "questions" to questions.map { (id, q) -> mapOf("id" to id, "instructions" to q.instructions(), "criteria" to q.criteria()) })))
        val file = cacheDirectory?.resolve("$key.json")
        if (file?.exists() == true) runCatching { json.decodeFromString<Map<String, Double>>(file.readText()) }.getOrNull()
            ?.takeIf { a -> a.keys == questions.keys && a.values.all { it.isFinite() && it in 0.0..1.0 } }?.let { reused.incrementAndGet(); return it }
        if (System.currentTimeMillis() < blockedUntil) return null
        val id = UUID.randomUUID().toString()
        calls.incrementAndGet()
        return try {
            val evaluation = c.evaluate(state, *questions.values.toTypedArray())
            val usage = evaluation.usage()
            val rawCost = evaluation.rawResponse().path("usage").path("cost_usd")
            val cost = rawCost.takeIf { it.isNumber }?.asDouble()?.takeIf { it.isFinite() && it >= 0 }
            val tokens = usage.inputTokens().orElse(0).toLong().takeIf { it > 0 }
                ?: Guidelines.weight(mapper.writeValueAsString(mapOf("state" to state, "questions" to questions.values.map { q -> mapOf("instructions" to q.instructions(), "criteria" to q.criteria()) }))).toLong()
            UsageScope.record(AiUsage(id, "JEV", evaluation.model(), "none", costUsd = cost ?: tokens * INPUT_USD_PER_TOKEN,
                inputTokens = tokens, outputTokens = usage.outputTokens().orElse(0),
                status = if (cost == null) "ESTIMATED" else "CONFIRMED", questions = questions.size))
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
        const val INPUT_USD_PER_TOKEN = 0.042 / 1_000_000
        private var cachedKey: String? = null
        private var cached: Jev? = null
        private fun key() = (listOfNotNull(JevKey.also { it.refresh() }.fromServer) + listOf("TYPESAFE_API_KEY", "JEV_API_KEY").mapNotNull { System.getenv(it) }).firstOrNull { it.isNotBlank() }?.trim()

        private fun client(key: String?) = key?.let { TypeSafeJevClient.builder().apiKey(it).timeout(Duration.ofSeconds(15)).maxRetries(1).build() }

        @Synchronized
        fun fromEnvironment(): Jev {
            val key = key()
            if (cached != null && key == cachedKey) return cached!!
            cachedKey = key
            return Jev(client(key), Path.of(System.getProperty("user.home"), ".ilsim", "decisions")).also { cached = it }
        }

        fun uncached(): Jev = Jev(client(key()))

        fun cached(directory: Path): Jev = Jev(client(key()), directory)
    }
}
