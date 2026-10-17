package com.dochiri.ilsim.jev

import com.dochiri.ilsim.jev.adapter.out.typesafe.TypeSafeJevGateway
import com.dochiri.ilsim.jev.api.JevQuestion
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.gudcks0305.jev.JevException
import io.github.gudcks0305.jev.spi.JevTransport
import io.github.gudcks0305.jev.typesafe.TypeSafeJevClient
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class JevTest {

    private class FakeTransport(private val response: String, private val fail: Boolean) : JevTransport {
        val request = AtomicReference<JsonNode>()
        val headers = AtomicReference<Map<String, String>>()

        override fun post(uri: URI, headers: Map<String, String>, body: JsonNode): CompletableFuture<JsonNode> {
            request.set(body)
            this.headers.set(headers)
            if (fail) return CompletableFuture.failedFuture(JevException(JevException.Kind.SERVER, "boom", 500))
            return try {
                CompletableFuture.completedFuture(MAPPER.readTree(response))
            } catch (e: Exception) {
                CompletableFuture.failedFuture(e)
            }
        }

        override fun close() {}
    }

    private fun gateway(transport: FakeTransport) =
        TypeSafeJevGateway.withClient(TypeSafeJevClient.builder().apiKey("secret").transport(transport).build())

    private val questions = linkedMapOf(
        "refund" to JevQuestion.bool("환불인가?"),
        "route" to JevQuestion.choice("분류", mapOf("billing" to "결제", "shipping" to "배송")),
        "quality" to JevQuestion.score("품질", listOf("poor", "good", "great")),
    )

    @Test
    fun `세 종류의 질문을 보내고 답을 해석한다`() {
        val transport = FakeTransport(
            """
            {"model":"jev-latest","answers":{
              "refund":{"type":"noul","noul":0.98},
              "route":{"type":"choice","choice":"billing","probabilities":{"billing":1,"shipping":0}},
              "quality":{"type":"score","score":1.9,"probabilities":{"0":0,"1":0.1,"2":0.9}}},
             "usage":{"input_tokens":275,"output_tokens":20}}""",
            false,
        )

        val answers = gateway(transport).evaluate(mapOf("k" to "v"), questions)!!

        assertThat(transport.request.get().path("questions").size()).isEqualTo(3)
        assertThat(transport.request.get().path("questions").path("quality").path("type").asText()).isEqualTo("score")
        assertThat(transport.headers.get().values.joinToString(",")).contains("secret")
        assertThat(answers.getValue("refund").probability).isEqualTo(0.98)
        assertThat(answers.getValue("route").choice).isEqualTo("billing")
        assertThat(answers.getValue("route").probabilities).containsEntry("billing", 1.0)
        assertThat(answers.getValue("quality").score).isEqualTo(1.9)
    }

    @Test
    fun `키가 없거나 호출이 실패하거나 응답이 계약과 다르면 빈 값을 돌려준다`() {
        assertThat(TypeSafeJevGateway.withClient(null).evaluate("s", questions)).isNull()
        assertThat(gateway(FakeTransport("{}", true)).evaluate("s", questions)).isNull()
        assertThat(gateway(FakeTransport("""{"answers":{}}""", false)).evaluate("s", questions)).isNull()
    }

    private companion object {
        val MAPPER = ObjectMapper()
    }
}
