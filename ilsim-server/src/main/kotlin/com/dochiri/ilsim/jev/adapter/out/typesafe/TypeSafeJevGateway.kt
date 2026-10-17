package com.dochiri.ilsim.jev.adapter.out.typesafe

import com.dochiri.ilsim.jev.api.JevAnswer
import com.dochiri.ilsim.jev.api.JevGateway
import com.dochiri.ilsim.jev.api.JevQuestion
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

/** TypeSafe JEV(jev-typesafe 라이브러리, code-risk-analyzer 와 같은 것) 어댑터. */
class TypeSafeJevGateway private constructor(
    private val fixed: LibClient?,
    private val keys: ApiKeySource?,
) : JevGateway, AutoCloseable {

    /** 저장소/환경에서 키를 읽는다. 키가 바뀌면 클라이언트를 다시 만든다. */
    constructor(keys: ApiKeySource) : this(null, keys)

    private var currentKey: String? = null
    private var current: LibClient? = null

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
        }
        return current
    }

    override fun enabled(): Boolean = lib() != null

    override fun evaluate(state: Any, questions: Map<String, JevQuestion>): Map<String, JevAnswer>? {
        val client = lib() ?: return null
        if (questions.isEmpty()) return null
        return try {
            val asked = questions.mapValues { (id, q) -> toLib(id, q) }
            val evaluation = client.evaluate(state, *asked.values.toTypedArray())
            asked.mapValues { (id, _) -> toAnswer(evaluation.answers()[id]) }
        } catch (e: JevException) {
            log.warn("JEV 호출 실패: {} (HTTP {})", e.kind(), e.statusCode())
            null
        } catch (e: RuntimeException) {
            log.warn("JEV 호출 실패: {}", e.javaClass.simpleName)
            null
        }
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

        /** 이미 만든 라이브러리 클라이언트(또는 null=비활성)를 그대로 쓴다. 테스트용. */
        fun withClient(client: LibClient?): TypeSafeJevGateway = TypeSafeJevGateway(client, null)
    }
}
