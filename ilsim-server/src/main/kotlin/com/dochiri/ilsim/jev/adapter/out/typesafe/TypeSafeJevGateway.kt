package com.dochiri.ilsim.jev.adapter.out.typesafe

import com.dochiri.ilsim.jev.api.JevAnswer
import com.dochiri.ilsim.jev.api.JevGateway
import com.dochiri.ilsim.jev.api.JevQuestion
import com.dochiri.ilsim.jev.api.JevUsage
import com.dochiri.ilsim.jev.application.port.out.ApiKeySource
import io.github.gudcks0305.jev.ChoiceAnswer
import io.github.gudcks0305.jev.ChoiceQuestion
import io.github.gudcks0305.jev.JevException
import io.github.gudcks0305.jev.NoulAnswer
import io.github.gudcks0305.jev.NoulQuestion
import io.github.gudcks0305.jev.ScoreAnswer
import io.github.gudcks0305.jev.ScoreQuestion
import io.github.gudcks0305.jev.typesafe.TypeSafeJevClient
import java.time.Duration
import org.slf4j.LoggerFactory
import io.github.gudcks0305.jev.Answer as LibAnswer
import io.github.gudcks0305.jev.JevClient as LibClient
import io.github.gudcks0305.jev.Question as LibQuestion

class TypeSafeJevGateway private constructor(
    private val fixed: LibClient?,
    private val keys: ApiKeySource?,
) : JevGateway, AutoCloseable {

    constructor(keys: ApiKeySource) : this(null, keys)

    private var currentKey: String? = null
    private var current: LibClient? = null
    private val usage = ThreadLocal.withInitial { mutableListOf<JevUsage>() }
    private var failures = 0
    private var blockedUntil = 0L

    override fun drainUsage(): List<JevUsage> = usage.get().toList().also { usage.get().clear() }

    @Synchronized
    private fun lib(): LibClient? {
        if (keys == null) return fixed
        val key = keys.apiKey()
        if (key == null) {
            current = null
            currentKey = null
            return null
        }
        if (key != currentKey) {
            current?.close()
            current = TypeSafeJevClient.builder().apiKey(key).timeout(Duration.ofSeconds(10)).maxRetries(1).build()
            currentKey = key
            failures = 0
            blockedUntil = 0
        }
        return current
    }

    override fun enabled(): Boolean = lib() != null

    override fun evaluate(state: Any, questions: Map<String, JevQuestion>): Map<String, JevAnswer>? {
        val client = lib() ?: return null
        if (questions.isEmpty()) return null
        if (System.currentTimeMillis() < blockedUntil) return null
        val callId = java.util.UUID.randomUUID().toString()
        return try {
            val asked = questions.mapValues { (id, q) -> toLib(id, q) }
            val evaluation = client.evaluate(state, *asked.values.toTypedArray())
            val rawCost = evaluation.rawResponse().path("usage").path("cost_usd")
            val cost = rawCost.takeIf { it.isNumber }?.asDouble()?.takeIf { it.isFinite() && it >= 0 }
            usage.get().add(JevUsage(callId, evaluation.model(), evaluation.usage().inputTokens().orElse(0), evaluation.usage().outputTokens().orElse(0), questions.size, cost))
            failures = 0
            asked.mapValues { (id, _) -> toAnswer(evaluation.answers()[id]) }
        } catch (e: JevException) {
            failed(callId, questions.size)
            log.warn("JEV 호출 실패: {} (HTTP {})", e.kind(), e.statusCode())
            null
        } catch (e: RuntimeException) {
            failed(callId, questions.size)
            log.warn("JEV 호출 실패: {}", e.javaClass.simpleName)
            null
        }
    }

    private fun failed(id: String, questions: Int) {
        failures++
        if (failures >= 2) blockedUntil = System.currentTimeMillis() + 60_000
        usage.get().add(JevUsage(id, "jev", 0, 0, questions))
    }

    private fun toLib(id: String, q: JevQuestion): LibQuestion<*> = when (q.type) {
        "boolean" -> NoulQuestion.of(id, q.instructions)
        "score" -> ScoreQuestion.of(id, q.instructions, q.criteria as List<*>)
        "choice" -> ChoiceQuestion.of(id, q.instructions, q.criteria as Map<String, *>)
        else -> throw IllegalArgumentException("지원하지 않는 질문 종류: ${q.type}")
    }

    private fun toAnswer(a: LibAnswer?): JevAnswer = when (a) {
        is NoulAnswer -> JevAnswer("boolean", probability = a.probability())
        is ScoreAnswer -> JevAnswer("score", probabilities = a.probabilities().entries.associate { it.key.toString() to it.value }, score = a.score())
        is ChoiceAnswer<*> -> JevAnswer("choice", choice = a.choice().toString(), probabilities = a.probabilities().entries.associate { it.key.toString() to it.value })
        else -> throw IllegalStateException("알 수 없는 답 종류")
    }

    override fun close() {
        current?.close()
        fixed?.close()
    }

    companion object {
        private val log = LoggerFactory.getLogger(TypeSafeJevGateway::class.java)

        fun withClient(client: LibClient?): TypeSafeJevGateway = TypeSafeJevGateway(client, null)
    }
}
