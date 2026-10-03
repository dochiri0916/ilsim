package com.example.coderiskanalyzer.analysis.callgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.example.coderiskanalyzer.production.analysis.callgraph.CallGraphBuilder;
import com.example.coderiskanalyzer.production.parser.JavaSourceParser;
import com.example.coderiskanalyzer.production.source.SourceFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CallGraphBuilderTests {

    @TempDir
    Path tempDir;

    @Test
    void buildsLocalAndExternalEdgesAndBoundsTraversalAcrossCycles() throws Exception {
        Path root = tempDir.resolve("src/main/java");
        Files.createDirectories(root.resolve("sample"));
        Path service = root.resolve("sample/Service.java");
        Path helper = root.resolve("sample/Helper.java");
        Files.writeString(service, "package sample; class Service { Helper helper; Repo repo; "
                + "void start() { helper.run(); repo.save(); } void finish() { helper.run(); } }");
        Files.writeString(helper, "package sample; class Helper { Service service; void run() { service.finish(); } }");
        var sources = new JavaSourceParser().parse(List.of(new SourceFile(root, service), new SourceFile(root, helper)));

        var graph = new CallGraphBuilder().build(sources);

        assertThat(graph.outgoing().get("sample.Service#start()"))
                .contains("sample.Helper#run()")
                .anyMatch(target -> target.startsWith("external:"));
        assertThat(graph.incoming()).containsKey("sample.Helper#run()");
        assertThat(graph.reachableFrom("sample.Service#start()", 5))
                .contains("sample.Helper#run()", "sample.Service#finish()", "sample.Service#start()");
    }
}
