package com.example.coderiskanalyzer.mutation;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.example.coderiskanalyzer.production.model.SourceLocation;
import com.example.coderiskanalyzer.testanalysis.CoverageStatus;
import com.example.coderiskanalyzer.testanalysis.TestAdequacyResult;
import com.example.coderiskanalyzer.testanalysis.TestObligation;

class MutationObligationMatcherTests {
    @Test
    void onlyMapsKnownMutatorToOneRelevantObligationAndLeavesAmbiguityUnmapped() {
        var boundary = adequacy("BOUNDARY", "stock < amount");
        var unrelatedBranch = adequacy("BRANCH", "condition true");
        var evidence = new MutationEvidence("example.Order", "canOrder", 15, "CONDITIONALS_BOUNDARY", MutationStatus.SURVIVED, "boundary");
        var matcher = new MutationObligationMatcher();

        assertThat(matcher.match(List.of(boundary, unrelatedBranch), List.of(evidence)).get(0).adequacy())
                .isEqualTo(boundary);
        assertThat(matcher.match(List.of(boundary, adequacy("BOUNDARY", "amount > 0")), List.of(evidence))
                .get(0).mappingStatus()).isEqualTo("UNMAPPED");
    }

    private TestAdequacyResult adequacy(String type, String condition) {
        var obligation = new TestObligation("O-1", type, condition, "example.Order", "canOrder",
                new SourceLocation(Path.of("Order.java"), 10, 1, 20, 1), "assert case", null);
        return new TestAdequacyResult(obligation, CoverageStatus.PARTIAL, List.of(), List.of(), List.of(), "static result");
    }
}
