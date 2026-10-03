package com.example.coderiskanalyzer.baseline;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.example.coderiskanalyzer.production.baseline.BaselineService;
import com.example.coderiskanalyzer.production.finding.Confidence;
import com.example.coderiskanalyzer.production.finding.Evidence;
import com.example.coderiskanalyzer.production.finding.Finding;
import com.example.coderiskanalyzer.production.finding.FindingStatus;
import com.example.coderiskanalyzer.production.finding.RiskCategory;
import com.example.coderiskanalyzer.production.finding.Severity;
import com.example.coderiskanalyzer.production.model.SourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BaselineServiceTests {

    @TempDir
    Path tempDir;

    @Test
    void createsStableBaselineAndFiltersExistingFindings() throws Exception {
        Path source = tempDir.resolve("src/main/java/Service.java");
        Finding baselineFinding = finding(source, 10);
        Finding newFinding = finding(source, 11);
        Path baseline = tempDir.resolve(".code-risk-baseline.json");
        BaselineService service = new BaselineService();

        service.create(tempDir, baseline, List.of(baselineFinding));
        var remaining = service.excludeExisting(tempDir, baseline, List.of(baselineFinding, newFinding));

        assertThat(Files.readString(baseline)).contains("TX-003|src/main/java/Service.java|10|pay");
        assertThat(remaining).containsExactly(newFinding);
    }

    private Finding finding(Path file, int line) {
        var location = new SourceLocation(file, line, 1, line, 10);
        return new Finding("TX-003", RiskCategory.TRANSACTION, Severity.MEDIUM, Confidence.HIGH,
                "Remote call", "Review", location,
                List.of(new Evidence("REMOTE", "Remote call", location, Map.of("method", "pay"))),
                FindingStatus.REVIEW);
    }
}
