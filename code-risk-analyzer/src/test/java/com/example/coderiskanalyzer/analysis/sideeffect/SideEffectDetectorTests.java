package com.example.coderiskanalyzer.analysis.sideeffect;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;

import com.example.coderiskanalyzer.production.analysis.AnalysisContext;
import com.example.coderiskanalyzer.production.analysis.sideeffect.SideEffectDetector;
import com.example.coderiskanalyzer.production.analysis.sideeffect.SideEffectType;
import com.example.coderiskanalyzer.production.cli.ScanCommand;
import com.example.coderiskanalyzer.production.cli.ScanRequest;
import com.example.coderiskanalyzer.production.model.ProgramModelBuilder;
import com.example.coderiskanalyzer.production.parser.JavaSourceParser;
import com.example.coderiskanalyzer.production.parser.ParsedSource;
import com.example.coderiskanalyzer.production.report.JsonReporter;
import com.example.coderiskanalyzer.production.rule.RuleEngine;
import com.example.coderiskanalyzer.production.rule.transaction.RemoteCallInsideTransactionRule;
import com.example.coderiskanalyzer.production.source.SourceDiscoveryService;
import com.example.coderiskanalyzer.production.source.SourceFile;
import com.example.coderiskanalyzer.production.source.SourceRootDetector;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SideEffectDetectorTests {
    @TempDir Path tempDir;
    private final SideEffectDetector detector = new SideEffectDetector();

    @Test
    void resolvesInheritedDatabaseApisRegardlessOfReceiverNameAndRejectsUnrelatedResolvedCalls() throws Exception {
        Path root = tempDir.resolve("src/main/java");
        write(root, "org/springframework/data/repository/CrudRepository.java", """
                package org.springframework.data.repository;
                public interface CrudRepository<T> { T save(T value); void delete(T value); T findById(int id); }
                """);
        var source = parse(root, """
                interface Orders extends org.springframework.data.repository.CrudRepository<String> { String save(String value); void save(int value); void deleteByName(String name); }
                class Local { void save(String value) {} void pay() {} void send() {} }
                class Calls {
                    Orders storage; Local repository; Local paymentClient; Local kafkaTemplate;
                    void run() { storage.save("a"); storage.delete("a"); storage.findById(1); storage.deleteByName("b"); storage.save(1);
                        repository.save("a"); paymentClient.pay(); kafkaTemplate.send(); }
                }
                """, List.of());
        assertThat(detector.detect(source)).extracting(effect -> effect.type())
                .containsExactly(SideEffectType.DATABASE_WRITE, SideEffectType.DATABASE_DELETE, SideEffectType.DATABASE_DELETE);
        assertThat(detector.withEvidence(source, calls(source), List.of()))
                .allSatisfy(evidence -> assertThat(evidence.attributes().get("basis")).isEqualTo("RESOLVED_TYPE"));
    }

    @Test
    void distinguishesOverloadsAndDoesNotInferResolvedJdkCallsFromNames() throws Exception {
        Path root = tempDir.resolve("src/main/java");
        write(root, "org/springframework/web/client/RestOperations.java", """
                package org.springframework.web.client;
                public interface RestOperations { String postForObject(String url); }
                """);
        var source = parse(root, """
                class LocalBase { void postForObject(int value) {} }
                class Transport extends LocalBase implements org.springframework.web.client.RestOperations {
                    public String postForObject(String url) { return url; }
                }
                class Calls { Transport wire; java.util.List<String> repository;
                    void run() { wire.postForObject("https://example.test"); wire.postForObject(1);
                        repository.remove("a"); java.nio.file.Files.delete(java.nio.file.Path.of("x")); }
                }
                """, List.of());
        assertThat(detector.classify(calls(source).stream().filter(call -> call.toString().equals("wire.postForObject(1)"))
                .findFirst().orElseThrow())).isEmpty();
        assertThat(detector.detect(source)).extracting(effect -> effect.type())
                .containsExactly(SideEffectType.EXTERNAL_HTTP_CALL, SideEffectType.FILE_WRITE);
    }

    @Test
    void resolvesJdbcMessagesJdkHttpAndFeignWithoutNamingHints() throws Exception {
        Path root = tempDir.resolve("src/main/java");
        write(root, "org/springframework/jdbc/core/JdbcOperations.java",
                "package org.springframework.jdbc.core; public interface JdbcOperations { int update(String sql); }");
        write(root, "org/springframework/kafka/core/KafkaOperations.java",
                "package org.springframework.kafka.core; public interface KafkaOperations { void send(String value); }");
        write(root, "org/springframework/cloud/openfeign/FeignClient.java",
                "package org.springframework.cloud.openfeign; public @interface FeignClient {}");
        var source = parse(root, """
                @org.springframework.cloud.openfeign.FeignClient interface Payments {
                    void charge(); default void helper() {} static void util() {}
                }
                class Calls {
                    org.springframework.jdbc.core.JdbcOperations store;
                    org.springframework.kafka.core.KafkaOperations bus;
                    java.net.http.HttpClient transport; java.net.http.HttpRequest request;
                    Payments gateway;
                    void run() {
                        store.update("update orders set state = 1"); bus.send("event");
                        transport.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                        gateway.charge(); gateway.helper(); Payments.util();
                        Runtime.getRuntime().exec("program");
                    }
                }
                """, List.of());
        assertThat(detector.detect(source)).extracting(effect -> effect.type()).containsExactly(
                SideEffectType.DATABASE_WRITE, SideEffectType.MESSAGE_PUBLISH,
                SideEffectType.EXTERNAL_HTTP_CALL, SideEffectType.EXTERNAL_HTTP_CALL, SideEffectType.PROCESS_EXECUTION);
    }

    @Test
    void unresolvedCallsKeepHeuristicEvidenceOnRuleFindings() throws Exception {
        Path root = tempDir.resolve("src/main/java");
        var source = parse(root, "class Calls { @Transactional void run() { paymentClient.pay(); } }", List.of());
        var findings = new RemoteCallInsideTransactionRule(detector).analyze(
                new AnalysisContext(new ProgramModelBuilder().build(List.of(source)), List.of(source)));
        assertThat(findings).singleElement().satisfies(finding -> assertThat(finding.evidence())
                .anySatisfy(evidence -> {
                    assertThat(evidence.type()).isEqualTo("SIDE_EFFECT_CLASSIFICATION");
                    assertThat(evidence.attributes().get("basis")).isEqualTo("NAME_HEURISTIC");
                    assertThat(evidence.message()).contains("resolution failed");
                }));
    }

    @Test
    void externalJarResolvesHttpAndDatabaseCallsAndIsUsedByScanAndBaseline() throws Exception {
        Path jar = createJar();
        Path root = tempDir.resolve("src/main/java");
        var source = parse(root, """
                class Calls { org.springframework.web.client.RestOperations transport;
                    org.springframework.data.repository.CrudRepository<String> storage;
                    @Transactional void run() { storage.save("a"); transport.postForObject("https://example.test"); }
                }
                """, List.of(jar, jar));
        assertThat(calls(source).getFirst().resolve().getQualifiedSignature()).contains("CrudRepository.save");
        assertThat(detector.detect(source)).extracting(effect -> effect.type())
                .containsExactly(SideEffectType.DATABASE_WRITE, SideEffectType.EXTERNAL_HTTP_CALL);
        var withoutJar = new JavaSourceParser().parse(List.of(source.sourceFile()), List.of(root)).getFirst();
        assertThat(detector.detect(withoutJar)).isEmpty();
        Files.writeString(tempDir.resolve(".code-risk.yml"), "analysis:\n  classpath: [libs/api.jar]\n");
        var command = new ScanCommand(new SourceDiscoveryService(new SourceRootDetector()), new JavaSourceParser(),
                new ProgramModelBuilder(), new RuleEngine(List.of(new RemoteCallInsideTransactionRule(detector))),
                List.of(new JsonReporter()));
        var response = command.scanForCli(new ScanRequest(tempDir.toString(), "json", "", "false", "", "", "", false));
        assertThat(response.exitCode()).isZero();
        assertThat(response.output()).contains("TX-003", "RESOLVED_TYPE", "RestOperations.postForObject");
        command.createBaseline(tempDir.toString(), "baseline.json");
        assertThat(Files.readString(tempDir.resolve("baseline.json"))).contains("TX-003");
        Files.writeString(tempDir.resolve(".code-risk.yml"), "analysis:\n  classpath: [libs/missing.jar]\n");
        assertThat(command.scanForCli(new ScanRequest(tempDir.toString(), "json", "", "false", "", "", "", false))
                .exitCode()).isEqualTo(2);
    }

    @Test
    void classDirectoryAndJarPatternResolveTheSameCallsInAScan() throws Exception {
        createJar();
        Path root = tempDir.resolve("src/main/java");
        write(root, "Calls.java", """
                class Calls { org.springframework.web.client.RestOperations transport;
                    @Transactional void run() { transport.postForObject("https://example.test"); }
                }
                """);
        var command = new ScanCommand(new SourceDiscoveryService(new SourceRootDetector()), new JavaSourceParser(),
                new ProgramModelBuilder(), new RuleEngine(List.of(new RemoteCallInsideTransactionRule(detector))),
                List.of(new JsonReporter()));
        for (String classpath : List.of("[library-classes]", "['libs/*.jar']", "['**/api.jar', libs/api.jar]")) {
            Files.writeString(tempDir.resolve(".code-risk.yml"), "analysis:\n  classpath: " + classpath + "\n");
            var response = command.scanForCli(new ScanRequest(tempDir.toString(), "json", "", "false", "", "", "", false));
            assertThat(response.exitCode()).as(classpath).isZero();
            assertThat(response.output()).as(classpath).contains("TX-003", "RESOLVED_TYPE", "RestOperations.postForObject");
        }
    }

    private ParsedSource parse(Path root, String text, List<Path> jars) throws Exception {
        Path file = write(root, "Calls.java", text);
        return new JavaSourceParser().parse(List.of(new SourceFile(root, file)), List.of(root), jars).getFirst();
    }

    private List<MethodCallExpr> calls(ParsedSource source) {
        return source.compilationUnit().findAll(MethodCallExpr.class);
    }

    private Path write(Path root, String name, String contents) throws Exception {
        Path file = root.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents);
        return file;
    }

    private Path createJar() throws Exception {
        Path sources = tempDir.resolve("library-src");
        Path classes = tempDir.resolve("library-classes");
        Files.createDirectories(classes);
        var definitions = Map.of(
                "org/springframework/web/client/RestOperations.java",
                "package org.springframework.web.client; public interface RestOperations { String postForObject(String url); }",
                "org/springframework/data/repository/CrudRepository.java",
                "package org.springframework.data.repository; public interface CrudRepository<T> { T save(T value); }");
        var args = new java.util.ArrayList<>(List.of("-d", classes.toString()));
        for (var entry : definitions.entrySet()) args.add(write(sources, entry.getKey(), entry.getValue()).toString());
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new))).isZero();
        Path jar = tempDir.resolve("libs/api.jar");
        Files.createDirectories(jar.getParent());
        try (var output = new JarOutputStream(Files.newOutputStream(jar)); var entries = Files.walk(classes)) {
            for (Path file : entries.filter(Files::isRegularFile).toList()) {
                output.putNextEntry(new JarEntry(classes.relativize(file).toString()));
                Files.copy(file, output);
                output.closeEntry();
            }
        }
        return jar;
    }
}
