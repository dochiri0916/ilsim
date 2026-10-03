package com.example.coderiskanalyzer.parser;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.example.coderiskanalyzer.production.parser.JavaSourceParser;
import com.example.coderiskanalyzer.production.parser.ParsedSource;
import com.example.coderiskanalyzer.production.source.SourceFile;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaSourceParserTests {

    @TempDir
    Path tempDir;

    @Test
    void parsesModernJavaAndRetainsPerFileErrors() throws Exception {
        Path root = tempDir.resolve("src/main/java");
        Files.createDirectories(root);
        Path valid = root.resolve("Point.java");
        Path invalid = root.resolve("Broken.java");
        Files.writeString(valid, "record Point(int x, int y) {}\n");
        Files.writeString(invalid, "class Broken { void f( { }\n");

        List<ParsedSource> results = new JavaSourceParser().parse(List.of(
                new SourceFile(root, valid), new SourceFile(root, invalid)));

        assertThat(results).hasSize(2);
        assertThat(results.get(0).compilationUnit()).isNotNull();
        assertThat(results.get(1).errors()).isNotEmpty();
    }

    @Test
    void resolvesCallsAcrossOverloadsTypesInheritanceStaticMethodsAndPackages() throws Exception {
        Path main = tempDir.resolve("src/main/java");
        Path tests = tempDir.resolve("src/test/java");
        write(main, "sample/api/Worker.java", "package sample.api; public interface Worker { int process(int value); }");
        write(main, "sample/impl/WorkerImpl.java", "package sample.impl; public class WorkerImpl implements sample.api.Worker { public int process(int value) { return value; } }");
        write(main, "sample/base/BaseWorker.java", "package sample.base; public class BaseWorker { public int inherited(int value) { return value; } }");
        write(main, "sample/base/ChildWorker.java", "package sample.base; public class ChildWorker extends BaseWorker {}");
        write(main, "sample/dup/First.java", "package sample.dup; public class First { public static int calculate(int value) { return value; } }");
        write(main, "sample/dup/Second.java", "package sample.dup; public class Second { public static int calculate(int value) { return value; } }");
        write(main, "sample/tools/Tools.java", "package sample.tools; public class Tools { public static int scale(int value) { return value; } }");
        write(main, "sample/one/Task.java", "package sample.one; public class Task { public int run() { return 1; } }");
        write(main, "sample/two/Task.java", "package sample.two; public class Task { public int run() { return 2; } }");
        Path test = write(tests, "sample/Calls.java", """
                package sample;
                class Calls {
                    void exercise() {
                        sample.api.Worker api = new sample.impl.WorkerImpl(); api.process(1);
                        new sample.impl.WorkerImpl().process(2);
                        new sample.base.ChildWorker().inherited(3);
                        sample.dup.First.calculate(4);
                        sample.tools.Tools.scale(5);
                        new sample.one.Task().run();
                        new sample.two.Task().run();
                    }
                }
                """);
        List<SourceFile> files = List.of(
                new SourceFile(main, main.resolve("sample/api/Worker.java")),
                new SourceFile(main, main.resolve("sample/impl/WorkerImpl.java")),
                new SourceFile(main, main.resolve("sample/base/BaseWorker.java")),
                new SourceFile(main, main.resolve("sample/base/ChildWorker.java")),
                new SourceFile(main, main.resolve("sample/dup/First.java")),
                new SourceFile(main, main.resolve("sample/dup/Second.java")),
                new SourceFile(main, main.resolve("sample/tools/Tools.java")),
                new SourceFile(main, main.resolve("sample/one/Task.java")),
                new SourceFile(main, main.resolve("sample/two/Task.java")),
                new SourceFile(tests, test));

        var parsed = new JavaSourceParser().parse(files, List.of(main, tests));
        var calls = parsed.stream().filter(source -> source.sourceFile().path().equals(test))
                .findFirst().orElseThrow().compilationUnit().findAll(MethodCallExpr.class);
        java.util.Map<String, String> resolvedOwners = new java.util.HashMap<>();
        for (MethodCallExpr call : calls) {
            var declaration = call.resolve();
            resolvedOwners.put(call.toString(), declaration.declaringType().getQualifiedName());
        }

        assertThat(resolvedOwners).containsEntry("api.process(1)", "sample.api.Worker")
                .containsEntry("new sample.impl.WorkerImpl().process(2)", "sample.impl.WorkerImpl")
                .containsEntry("new sample.base.ChildWorker().inherited(3)", "sample.base.BaseWorker")
                .containsEntry("sample.dup.First.calculate(4)", "sample.dup.First")
                .containsEntry("sample.tools.Tools.scale(5)", "sample.tools.Tools")
                .containsEntry("new sample.one.Task().run()", "sample.one.Task")
                .containsEntry("new sample.two.Task().run()", "sample.two.Task");
    }

    private Path write(Path root, String relative, String contents) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents);
        return file;
    }
}
