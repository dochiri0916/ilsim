package com.example.coderiskanalyzer.requirements;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.gudcks0305.jev.spi.JevTransport;
import io.github.gudcks0305.jev.typesafe.TypeSafeJevClient;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class JevRequirementsJudgeTests {
    @Test
    void realSdkSerializesSeparateQuestionsWithoutTurningProbabilitiesIntoSafetyApproval() {
        RecordingTransport transport = new RecordingTransport();
        try (var judge = new JevRequirementsJudge(TypeSafeJevClient.builder().apiKey("test-placeholder").transport(transport).build())) {
            var mapper = new ObjectMapper();
            var document = mapper.createObjectNode();
            document.putObject("requirements").putObject("REQ-EXAMPLE").put("source", "contradictory excerpts");
            var result = judge.reviewDocuments(document);
            assertThat(transport.request.path("questions").properties()).extracting(Map.Entry::getKey)
                    .containsExactly("document_conflict", "missing_scenario");
            assertThat(result.path("status").asText()).isEqualTo("REVIEW_REQUIRED");
            assertThat(result.path("assessments").get(0).path("probability").asDouble()).isEqualTo(0.95);
            assertThat(result.toString()).doesNotContain("EVIDENCE_SUPPORTED", "SAFE", "CONFIRMED");
            var code = document.deepCopy();
            code.putObject("staticEvidence").put("status", "PARTIAL");
            judge.reviewCode(code);
            assertThat(transport.request.path("questions").properties()).extracting(Map.Entry::getKey)
                    .containsExactly("implementation_mismatch");
            assertThat(transport.request.path("state").has("staticEvidence")).isTrue();
        }
    }

    private static class RecordingTransport implements JevTransport {
        JsonNode request;
        @Override public CompletableFuture<JsonNode> post(URI uri, Map<String,String> headers, JsonNode body) {
            request = body;
            var response = new ObjectMapper().createObjectNode();
            var answers = response.putObject("answers");
            body.path("questions").properties().forEach(entry -> answers.putObject(entry.getKey()).put("type", "noul").put("noul", 0.95));
            return CompletableFuture.completedFuture(response);
        }
        @Override public void close() {}
    }
}
