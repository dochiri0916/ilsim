package com.example.coderiskanalyzer.rule.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.example.coderiskanalyzer.production.analysis.AnalysisContext;
import com.example.coderiskanalyzer.production.analysis.sideeffect.SideEffectDetector;
import com.example.coderiskanalyzer.production.model.ProgramModelBuilder;
import com.example.coderiskanalyzer.production.parser.JavaSourceParser;
import com.example.coderiskanalyzer.production.finding.Finding;
import com.example.coderiskanalyzer.production.rule.idempotency.MessageConsumerSideEffectRule;
import com.example.coderiskanalyzer.production.source.SourceFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MessageConsumerSideEffectRuleTests {

    @TempDir
    Path tempDir;

    @Test
    void reportsListenerSideEffectWithoutDeduplication() throws Exception {
        assertThat(analyze("@KafkaListener void consume(Event event) { paymentClient.charge(event); repository.save(event); }"))
                .singleElement().satisfies(finding -> {
                    assertThat(finding.ruleId()).isEqualTo("IDEM-001");
                    assertThat(finding.status().name()).isEqualTo("REVIEW");
                    assertThat(finding.evidence()).hasSize(5);
                    assertThat(finding.evidence().stream().filter(item -> item.type().equals("SIDE_EFFECT_CLASSIFICATION")))
                            .hasSize(2).allSatisfy(item -> assertThat(item.attributes().get("basis")).isEqualTo("NAME_HEURISTIC"));
                });
    }

    @Test
    void ignoresListenerWithDeduplicationCheckOrNoSideEffect() throws Exception {
        assertThat(analyze("@RabbitListener void consume(Event event) { "
                + "if (repository.findProcessedEventId(event.id()) != null) return; repository.save(event); }"))
                .isEmpty();
        assertThat(analyze("@JmsListener void consume(Event event) { log.info(event.toString()); }"))
                .isEmpty();
    }

    private List<Finding> analyze(String method) throws Exception {
        Path root = tempDir.resolve("src/main/java");
        Files.createDirectories(root);
        Path file = root.resolve("Consumer.java");
        Files.writeString(file, "class Consumer { " + method + " }");
        var sources = new JavaSourceParser().parse(List.of(new SourceFile(root, file)));
        var context = new AnalysisContext(new ProgramModelBuilder().build(sources), sources);
        return new MessageConsumerSideEffectRule(new SideEffectDetector()).analyze(context);
    }
}
