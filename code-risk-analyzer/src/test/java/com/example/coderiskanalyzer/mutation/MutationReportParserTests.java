package com.example.coderiskanalyzer.mutation;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

class MutationReportParserTests {
    @TempDir Path temporary;

    @Test
    void parsesPitsMachineReadableStatusesAndUnknownValues() throws Exception {
        Path xml = temporary.resolve("mutations.xml");
        Files.writeString(xml, """
                <mutations>
                  <mutation detected="true" status="KILLED"><mutatedClass>example.Order</mutatedClass><mutatedMethod>canOrder</mutatedMethod><lineNumber>12</lineNumber><mutator>org.pitest.mutationtest.engine.gregor.mutators.ConditionalsBoundaryMutator</mutator><description>changed conditional boundary</description></mutation>
                  <mutation status="FUTURE_STATUS"><mutatedClass>example.Order</mutatedClass><mutatedMethod>canOrder</mutatedMethod><lineNumber>13</lineNumber><mutator>unknown</mutator><description>future status</description></mutation>
                </mutations>
                """);

        var mutations = new MutationReportParser().parse(xml);

        assertThat(mutations).extracting(MutationEvidence::status).containsExactly(MutationStatus.KILLED, MutationStatus.UNKNOWN);
        assertThat(mutations.get(0).line()).isEqualTo(12);
    }

    @Test
    void rejectsMalformedMutationXml() throws Exception {
        Path xml = temporary.resolve("invalid.xml");
        Files.writeString(xml, "<mutations><mutation>");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new MutationReportParser().parse(xml))
                .isInstanceOf(java.io.IOException.class);
    }
}
