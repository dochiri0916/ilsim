package com.example.coderiskanalyzer.testanalysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import com.example.coderiskanalyzer.production.parser.JavaSourceParser;
import com.example.coderiskanalyzer.production.parser.ParsedSource;
import com.example.coderiskanalyzer.production.source.SourceFile;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExecutionEvidenceLinkerTests {
    @TempDir Path root;
    Path production;
    Path test;
    List<ParsedSource> sources;
    TestAdequacyResult rollback;
    ObjectNode entry;
    ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setup() throws Exception {
        production = root.resolve("src/main/java/sample/Worker.java");
        test = root.resolve("src/test/java/sample/WorkerTest.java");
        Files.createDirectories(production.getParent());
        Files.createDirectories(test.getParent());
        Files.writeString(production, """
                package sample;
                class Worker {
                    @Transactional void run() {
                        repository.save(1);
                        repository.save(2);
                        throw new IllegalArgumentException();
                    }
                }
                """);
        Files.writeString(test, """
                package sample;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;
                class WorkerTest {
                    @Test void rollsBack() { assertThrows(IllegalArgumentException.class, () -> new Worker().run()); }
                }
                """);
        sources = new JavaSourceParser().parse(List.of(new SourceFile(root.resolve("src/main/java"), production),
                new SourceFile(root.resolve("src/test/java"), test)));
        rollback = new TestAdequacyAnalyzer().analyze(sources, List.of()).stream()
                .filter(r -> r.obligation().type().equals("TRANSACTION_ROLLBACK")).findFirst().orElseThrow();
        assertThat(rollback.status()).isEqualTo(CoverageStatus.PARTIAL);
        junit("<testcase classname='sample.WorkerTest' name='rollsBack()'/>");
        jacoco("<line nr='4' mi='0' ci='3'/><line nr='5' mi='0' ci='3'/><line nr='6' mi='0' ci='3'/>");
        entry = mapper.createObjectNode();
        entry.put("obligationId", rollback.obligation().id());
        entry.put("test", "sample.WorkerTest#rollsBack");
        entry.put("scenario", "ROLLBACK");
        entry.put("database", "PostgreSQL test container");
        entry.put("databaseEvidence", "Reviewer checked fresh DB reads after transaction failure.");
        entry.put("sourceSha256", hash(production));
        entry.put("testSourceSha256", hash(test));
        artifact("junit", "junit.xml");
        artifact("jacoco", "jacoco.xml");
    }

    @Test
    void coverageAndPassingTestDoNotReplaceAssertionReview() throws Exception {
        var result = link(rollback);
        assertThat(result.status()).isEqualTo(CoverageStatus.PARTIAL);
        assertThat(result.missingCases()).contains(rollback.obligation().requiredEvidence());
        assertThat(result.executionEvidence().getFirst().attributes()).containsEntry("testPassed", true)
                .containsEntry("methodLinesCovered", true).containsEntry("assertionReviewed", false);
    }

    @Test
    void promotesOnlyReviewedMatchingDbScenarioAndPreservesStaticStatus() throws Exception {
        review();
        var result = link(rollback);
        assertThat(result.status()).isEqualTo(CoverageStatus.COVERED);
        assertThat(result.missingCases()).isEmpty();
        assertThat(result.executionEvidence().getFirst().attributes()).containsEntry("staticStatus", "PARTIAL")
                .containsEntry("databaseVerifiedByAnalyzer", false).containsEntry("acceptedForObligation", true);
        assertThat(new TestAdequacyFindings().convert(List.of(result)).getFirst().evidence())
                .extracting(e -> e.type()).containsExactly("TEST_OBLIGATION", "EXECUTION_TEST");
        assertThat(link(rollback)).isEqualTo(result);
    }

    @Test
    void rejectsFailedSkippedDuplicateAndAbsentExecutionsForPromotion() throws Exception {
        review();
        for (String cases : List.of("<testcase classname='sample.WorkerTest' name='rollsBack'><failure/></testcase>",
                "<testcase classname='sample.WorkerTest' name='rollsBack'><error/></testcase>",
                "<testcase classname='sample.WorkerTest' name='rollsBack'><skipped/></testcase>",
                "<testcase classname='sample.WorkerTest' name='rollsBack'/><testcase classname='sample.WorkerTest' name='rollsBack'/>",
                "<testcase classname='other.WorkerTest' name='rollsBack'/>",
                "<testcase classname='sample.WorkerTest' name='rollsBack[1]'/>") ) {
            junit(cases);
            artifact("junit", "junit.xml");
            assertThat(link(rollback).status()).isEqualTo(CoverageStatus.PARTIAL);
        }
    }

    @Test
    void requiresExecutableLinesWithNoMissedInstructionsInCorrectFileAndPackage() throws Exception {
        review();
        for (String lines : List.of("", "<line nr='4' mi='1' ci='2'/>", "<line nr='1' mi='0' ci='2'/>",
                "<line nr='4' mi='0' ci='0'/>")) {
            jacoco(lines);
            artifact("jacoco", "jacoco.xml");
            assertThat(link(rollback).status()).isEqualTo(CoverageStatus.PARTIAL);
        }
        Files.writeString(root.resolve("jacoco.xml"), "<report><package name='other'><sourcefile name='Worker.java'><line nr='4' mi='0' ci='2'/></sourcefile></package></report>");
        artifact("jacoco", "jacoco.xml");
        assertThat(link(rollback).status()).isEqualTo(CoverageStatus.PARTIAL);
    }

    @Test
    void refusesWrongScenarioAndUnrelatedObligationTypes() throws Exception {
        review();
        entry.put("scenario", "CONCURRENCY");
        assertThat(link(rollback).status()).isEqualTo(CoverageStatus.PARTIAL);
        var obligation = rollback.obligation();
        for (String risk : List.of("CON-001", "CON-002", "IDEM-001", "SEC-001")) {
            var target = new TestObligation(obligation.id(), "RISK", "risk", obligation.owner(), obligation.method(),
                    obligation.location(), obligation.requiredEvidence(), risk);
            var partial = new TestAdequacyResult(target, CoverageStatus.PARTIAL, rollback.matchingTests(),
                    List.of(), List.of(target.requiredEvidence()), "static");
            entry.put("scenario", risk.equals("IDEM-001") ? "DUPLICATE_MESSAGE" : "CONCURRENCY");
            assertThat(link(partial).status()).isEqualTo(risk.equals("SEC-001") ? CoverageStatus.PARTIAL : CoverageStatus.COVERED);
        }
    }

    @Test
    void rejectsStaleSourcesAndReports() throws Exception {
        for (Path file : List.of(production, test, root.resolve("junit.xml"), root.resolve("jacoco.xml"))) {
            String original = Files.readString(file);
            Files.writeString(file, original + "\n");
            assertThatThrownBy(() -> link(rollback)).hasMessageContaining("hash mismatch");
            Files.writeString(file, original);
        }
    }

    @Test
    void rejectsUnlinkedUnknownDuplicateAndIncompleteEvidence() throws Exception {
        entry.put("test", "sample.WorkerTest#other");
        assertThatThrownBy(() -> link(rollback)).hasMessageContaining("not statically linked");
        entry.put("test", "sample.WorkerTest#rollsBack");
        entry.put("obligationId", "unknown");
        assertThatThrownBy(() -> link(rollback)).hasMessageContaining("outside the current scan");
        entry.put("obligationId", rollback.obligation().id());
        writeManifest(true);
        assertThatThrownBy(() -> new ExecutionEvidenceLinker().link(root, "evidence.json", List.of(rollback), sources))
                .hasMessageContaining("duplicate execution obligation");
        entry.remove("databaseEvidence");
        assertThatThrownBy(() -> link(rollback)).hasMessageContaining("databaseEvidence");
    }

    @Test
    void rejectsEscapingSymlinkPathsAndXmlEntities() throws Exception {
        entry.put("junit", "../junit.xml");
        assertThatThrownBy(() -> link(rollback)).hasMessageContaining("project-relative");
        entry.put("junit", root.resolve("junit.xml").toString());
        assertThatThrownBy(() -> link(rollback)).hasMessageContaining("project-relative");
        Files.createSymbolicLink(root.resolve("linked.xml"), root.resolve("junit.xml"));
        entry.put("junit", "linked.xml");
        assertThatThrownBy(() -> link(rollback)).hasMessageContaining("symbolic links");
        entry.put("junit", "junit.xml");
        Files.writeString(root.resolve("junit.xml"), "<!DOCTYPE testsuite [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><testsuite>&x;</testsuite>");
        artifact("junit", "junit.xml");
        assertThatThrownBy(() -> link(rollback)).hasMessageContaining("internal XML declarations");
        Files.writeString(root.resolve("junit.xml"), "<testsuite>");
        artifact("junit", "junit.xml");
        assertThatThrownBy(() -> link(rollback)).hasMessageContaining("XML");
    }

    @Test
    void rejectsAssertionReviewForDifferentBehaviorAndMalformedCounters() throws Exception {
        review();
        ((ObjectNode) entry.get("assertionReview")).put("requiredEvidence", "different behavior");
        assertThatThrownBy(() -> link(rollback)).hasMessageContaining("required evidence");
        review();
        jacoco("<line nr='4' mi='-1' ci='3'/>");
        artifact("jacoco", "jacoco.xml");
        assertThatThrownBy(() -> link(rollback)).hasMessageContaining("counter");
        jacoco("<line nr='4' mi='0' ci='3'/><line nr='4' mi='0' ci='3'/>");
        artifact("jacoco", "jacoco.xml");
        assertThatThrownBy(() -> link(rollback)).hasMessageContaining("duplicate JaCoCo");
    }

    private void review() {
        entry.putObject("assertionReview").put("reviewer", "maintainer").put("reason", "Checked failure injection and committed DB state assertions.")
                .put("requiredEvidence", rollback.obligation().requiredEvidence());
    }

    private TestAdequacyResult link(TestAdequacyResult result) throws Exception {
        writeManifest(false);
        return new ExecutionEvidenceLinker().link(root, "evidence.json", List.of(result), sources).getFirst();
    }

    private void writeManifest(boolean duplicate) throws Exception {
        var manifest = mapper.createObjectNode().put("version", 1);
        var entries = manifest.putArray("entries").add(entry);
        if (duplicate) entries.add(entry);
        Files.writeString(root.resolve("evidence.json"), mapper.writeValueAsString(manifest));
    }

    private void junit(String cases) throws Exception {
        Files.writeString(root.resolve("junit.xml"), "<testsuite>" + cases + "</testsuite>");
    }

    private void jacoco(String lines) throws Exception {
        Files.writeString(root.resolve("jacoco.xml"), "<!DOCTYPE report PUBLIC '-//JACOCO//DTD Report 1.1//EN' 'report.dtd'><report><package name='sample'><sourcefile name='Worker.java'>"
                + lines + "</sourcefile></package></report>");
    }

    private void artifact(String name, String path) throws Exception {
        entry.put(name, path).put(name + "Sha256", hash(root.resolve(path)));
    }

    private String hash(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
