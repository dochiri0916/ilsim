package com.dochiri.ilsim.runner.jev

import io.github.gudcks0305.jev.JevClient
import io.github.gudcks0305.jev.JevException
import io.github.gudcks0305.jev.NoulAnswer
import io.github.gudcks0305.jev.NoulQuestion
import io.github.gudcks0305.jev.typesafe.TypeSafeJevClient
import java.time.Duration

/**
 * TypeSafe JEV(결정 모델) 어댑터. 텍스트를 만들지 않고 yes/no 판단의 확률만 돌려주므로, 결과는 판단 근거로만 쓰고 최종 결정은 정책이 한다.
 * 키(환경 변수 TYPESAFE_API_KEY 또는 JEV_API_KEY)가 없거나 호출이 실패하면 null 을 돌려주어 호출한 쪽이 정적·규칙 기반 동작으로 되돌아가게 한다.
 */
/** 서버에 웹으로 등록된 키. 작업을 받을 때마다 갱신하며 메모리에만 둔다. 환경 변수보다 우선한다. */
object JevKey {
    @Volatile var fromServer: String? = null
}

class Jev(private val client: JevClient?) {

    val enabled: Boolean get() = client != null

    /** 질문 id → 확률(0..1). 한 번의 요청에 같은 상태로 여러 질문을 보낸다. 실패하면 null. */
    fun probabilities(state: Any, questions: Map<String, NoulQuestion>): Map<String, Double>? {
        val c = client ?: return null
        if (questions.isEmpty()) return emptyMap()
        return try {
            val evaluation = c.evaluate(state, *questions.values.toTypedArray())
            questions.mapValues { (_, q) -> evaluation.answer(q).probability() }
        } catch (e: JevException) {
            System.err.println("JEV 호출 실패: ${e.kind()} (HTTP ${e.statusCode()})")
            null
        } catch (e: RuntimeException) {
            System.err.println("JEV 호출 실패: ${e.javaClass.simpleName}")
            null
        }
    }

    fun probability(state: Any, question: NoulQuestion): Double? = probabilities(state, mapOf(question.id() to question))?.get(question.id())

    companion object {
        fun fromEnvironment(): Jev {
            val key = (listOfNotNull(JevKey.fromServer) + listOf("TYPESAFE_API_KEY", "JEV_API_KEY").mapNotNull { System.getenv(it) }).firstOrNull { it.isNotBlank() }?.trim()
            return Jev(key?.let { TypeSafeJevClient.builder().apiKey(it).timeout(Duration.ofSeconds(15)).maxRetries(1).build() })
        }
    }
}
