package com.example.coderiskanalyzer.rule.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.example.coderiskanalyzer.production.analysis.AnalysisContext;
import com.example.coderiskanalyzer.production.analysis.dataflow.DataFlowAnalyzer;
import com.example.coderiskanalyzer.production.model.ProgramModelBuilder;
import com.example.coderiskanalyzer.production.parser.JavaSourceParser;
import com.example.coderiskanalyzer.production.finding.Finding;
import com.example.coderiskanalyzer.production.rule.security.SsrfRule;
import com.example.coderiskanalyzer.production.source.SourceFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SsrfRuleTests {

    @TempDir Path tempDir;

    @Test
    void reportsRequestInputUsedAsRestTemplateUri() throws Exception {
        assertThat(analyze("@RequestParam String url) { restTemplate.getForObject(url, String.class); }"))
                .singleElement().satisfies(finding -> {
                    assertThat(finding.ruleId()).isEqualTo("SEC-004");
                    assertThat(finding.status().name()).isEqualTo("REVIEW");
                });
    }

    @Test
    void reportsWebClientUriAndIgnoresUnrelatedInput() throws Exception {
        assertThat(analyze("@RequestParam String url) { webClient.get().uri(url).retrieve(); }"))
                .singleElement().satisfies(finding -> assertThat(finding.ruleId()).isEqualTo("SEC-004"));
        assertThat(analyze("@RequestParam String url) { restTemplate.getForObject(\"https://example.com\", String.class); }"))
                .isEmpty();
    }

    @Test
    void ignoresRequestUriAfterExplicitHostAllowlistRejection() throws Exception {
        assertThat(analyze("@RequestParam String url) { URI uri = URI.create(url); String host = uri.getHost(); "
                + "if (!allowedHosts.contains(host)) { throw new IllegalArgumentException(); } "
                + "restTemplate.getForObject(uri.toString(), String.class); }"))
                .isEmpty();
    }


    @Test
    void keepsWarningsForGuardsThatDoNotProtectTheRequest() throws Exception {
        String setup = "@RequestParam String url) { URI uri = URI.create(url); String host = uri.getHost(); ";
        String reject = "if (!allowedHosts.contains(host)) throw new IllegalArgumentException(); ";
        String request = "restTemplate.getForObject(uri.toString(), String.class); ";
        for (String body : List.of(
                request + reject,
                "if (enabled) { " + reject + "} " + request,
                "String otherHost = URI.create(url + \"other\").getHost(); if (!allowedHosts.contains(otherHost)) return; " + request,
                "if (!allowedHosts.contains(\"safe.example\")) return; " + request,
                "if (!url.contains(host)) return; " + request,
                "if (!allowedHosts.contains(host)) { if (enabled) return; } " + request,
                reject + "if (mutate()) {} " + request,
                reject + "String ignored = mutate(); " + request,
                reject + "mutate(); " + reject + request,
                reject + "uri = URI.create(url + \"other\"); " + request,
                reject + "url = url + \"other\"; restTemplate.getForObject(url, String.class);",
                "try { " + reject + "} catch (Exception ignored) {} " + request,
                "if (!allowedHosts.contains(host) && enabled) return; " + request)) {
            assertThat(analyze(setup + body + "}")).as(body).hasSize(1);
        }
    }

    @Test
    void acceptsMatchingHostOnEveryPathAndChecksEachRequest() throws Exception {
        String setup = "@RequestParam String url) { URI uri = URI.create(url); String host = uri.getHost(); ";
        String reject = "if (!allowedHosts.contains(host)) return; ";
        String request = "restTemplate.getForObject(uri.toString(), String.class); ";
        assertThat(analyze(setup + request + reject + request + "}")).hasSize(1);
        assertThat(analyze(setup + "if (enabled) { " + reject + "} else { " + reject + "} " + request + "}")).isEmpty();
        assertThat(analyze(setup + reject + "String target = uri.toString(); restTemplate.getForObject(target, String.class); }")).isEmpty();
        assertThat(analyze(setup + "if (allowedHosts.contains(host)) { " + request + "} }")).isEmpty();
        assertThat(analyze(setup + reject + "restTemplate.getForObject(url, String.class); }")).isEmpty();
    }

    private List<Finding> analyze(String method) throws Exception {
        Path root = tempDir.resolve("src/main/java");
        Files.createDirectories(root);
        Path file = root.resolve("Controller.java");
        Files.writeString(file, "class Controller { void proxy(" + method + " }");
        var sources = new JavaSourceParser().parse(List.of(new SourceFile(root, file)));
        var context = new AnalysisContext(new ProgramModelBuilder().build(sources), sources);
        return new SsrfRule(new DataFlowAnalyzer()).analyze(context);
    }
}
