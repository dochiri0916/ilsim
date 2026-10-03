package com.example.coderiskanalyzer;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.example.coderiskanalyzer.production.cli.ScanCommand;
import com.example.coderiskanalyzer.production.cli.ScanRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Scans integration-fixtures/static-rules-sample; each class documents its expectation in "// EXPECT:" comments. */
@SpringBootTest
class StaticRulesFixtureTests {

    private static final Path FIXTURE = Path.of("integration-fixtures/static-rules-sample");

    @Autowired
    private ScanCommand scanCommand;

    @TempDir
    Path tempDir;

    @Test
    void reportsOnlyTheRiskyMethodsAndNotTheSafeVariants() throws Exception {
        copyFixture();
        var response = scanCommand.scanForCli(new ScanRequest(tempDir.toString(), "json", "", "false",
                "off", "", "", false));
        JsonNode report = new ObjectMapper().readTree(response.output());

        Map<String, Integer> counts = new TreeMap<>();
        report.path("findings").forEach(finding -> {
            String rule = finding.path("ruleId").asText();
            if (rule.equals("TEST-001")) return;
            String file = Path.of(finding.path("location").path("file").asText()).getFileName().toString();
            counts.merge(rule + " " + file, 1, Integer::sum);
        });

        assertThat(counts).as(response.output()).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                Map.entry("SEC-001 SqlController.java", 1),
                Map.entry("SEC-001 Generated.java", 1),
                Map.entry("SEC-003 FileController.java", 1),
                Map.entry("SEC-005 Secrets.java", 1),
                Map.entry("CON-003 CounterService.java", 1),
                Map.entry("IDEM-004 OrderConsumer.java", 1),
                // IDEM-001 needs a dedup call by name; neither listener has one (the keyed one only avoids IDEM-004).
                Map.entry("IDEM-001 OrderConsumer.java", 2),
                Map.entry("TX-001 TransactionJoin.java", 2)));
    }

    @Test
    void reportsChangedFilesThatWereNotDiscoveredWithoutFailingTheScan() throws Exception {
        copyFixture();
        Files.writeString(tempDir.resolve("changed.txt"),
                "src/main/java/com/example/staticrules/Secrets.java\nsrc/main/java/com/example/Typo.java\nREADME.md\n");
        var response = scanCommand.scanForCli(new ScanRequest(tempDir.toString(), "json", "", "false",
                "off", "", "changed.txt", false));

        assertThat(response.exitCode()).isZero();
        assertThat(response.diagnostics()).filteredOn(d -> "CHANGED-001".equals(d.code()))
                .singleElement().satisfies(d -> assertThat(d.file().getFileName().toString()).isEqualTo("Typo.java"));
    }

    @Test
    void reportsUnreadableDirectoriesAndKeepsScanning() throws Exception {
        copyFixture();
        Path locked = tempDir.resolve("src/main/java/com/example/locked");
        Files.createDirectories(locked);
        Files.writeString(locked.resolve("Hidden.java"), "package com.example.locked; class Hidden {}");
        try {
            if (!locked.toFile().setReadable(false) || Files.isReadable(locked)) return; // e.g. running as root
            var response = scanCommand.scanForCli(new ScanRequest(tempDir.toString(), "json", "", "false",
                    "off", "", "", false));
            assertThat(response.diagnostics()).anyMatch(d -> "SOURCE-002".equals(d.code())
                    && d.file().getFileName().toString().equals("locked"));
            assertThat(response.output()).contains("SEC-005");
        } finally {
            locked.toFile().setReadable(true);
        }
    }

    private void copyFixture() throws Exception {
        List<Path> paths = new ArrayList<>();
        try (var files = Files.walk(FIXTURE)) { files.forEach(paths::add); }
        for (Path source : paths) {
            Path destination = tempDir.resolve(FIXTURE.relativize(source).toString());
            if (Files.isDirectory(source)) Files.createDirectories(destination);
            else Files.copy(source, destination);
        }
    }
}
