package com.dochiri.ilsim.jev;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.gudcks0305.jev.typesafe.TypeSafeJevClient;
import io.github.gudcks0305.jev.spi.JevTransport;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class JevTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static class FakeTransport implements JevTransport {
        final AtomicReference<JsonNode> request = new AtomicReference<>();
        final AtomicReference<Map<String, String>> headers = new AtomicReference<>();
        final String response;
        final boolean fail;

        FakeTransport(String response, boolean fail) {
            this.response = response;
            this.fail = fail;
        }

        @Override
        public CompletableFuture<JsonNode> post(URI uri, Map<String, String> headers, JsonNode body) {
            request.set(body);
            this.headers.set(headers);
            if (fail) return CompletableFuture.failedFuture(new io.github.gudcks0305.jev.JevException(io.github.gudcks0305.jev.JevException.Kind.SERVER, "boom", 500));
            try {
                return CompletableFuture.completedFuture(JSON.readTree(response));
            } catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
        }

        @Override
        public void close() {}
    }

    private JevClient client(FakeTransport transport) {
        return new JevClient(TypeSafeJevClient.builder().apiKey("secret").transport(transport).build());
    }

    private static final Map<String, JevClient.Question> QUESTIONS = new java.util.LinkedHashMap<>();

    static {
        QUESTIONS.put("refund", JevClient.Question.bool("환불인가?"));
        QUESTIONS.put("route", JevClient.Question.choice("분류", Map.of("billing", "결제", "shipping", "배송")));
        QUESTIONS.put("quality", JevClient.Question.score("품질", List.of("poor", "good", "great")));
    }

    @Test
    void 세_종류의_질문을_보내고_답을_해석한다() {
        var transport = new FakeTransport("""
                {"model":"jev-latest","answers":{
                  "refund":{"type":"noul","noul":0.98},
                  "route":{"type":"choice","choice":"billing","probabilities":{"billing":1,"shipping":0}},
                  "quality":{"type":"score","score":1.9,"probabilities":{"0":0,"1":0.1,"2":0.9}}},
                 "usage":{"input_tokens":275,"output_tokens":20}}""", false);

        var answers = client(transport).evaluate(Map.of("k", "v"), QUESTIONS).orElseThrow();

        assertThat(transport.request.get().path("questions").size()).isEqualTo(3);
        assertThat(transport.request.get().path("questions").path("quality").path("type").asText()).isEqualTo("score");
        assertThat(String.join(",", transport.headers.get().values())).contains("secret");
        assertThat(answers.get("refund").probability()).isEqualTo(0.98);
        assertThat(answers.get("route").choice()).isEqualTo("billing");
        assertThat(answers.get("route").probabilities()).containsEntry("billing", 1.0);
        assertThat(answers.get("quality").score()).isEqualTo(1.9);
    }

    @Test
    void 키가_없거나_호출이_실패하거나_응답이_계약과_다르면_빈_값을_돌려준다() {
        assertThat(new JevClient((io.github.gudcks0305.jev.JevClient) null).evaluate("s", QUESTIONS)).isEmpty();
        assertThat(client(new FakeTransport("{}", true)).evaluate("s", QUESTIONS)).isEmpty();
        assertThat(client(new FakeTransport("{\"answers\":{}}", false)).evaluate("s", QUESTIONS)).isEmpty();
    }
}
