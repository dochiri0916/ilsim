package com.example.coderiskanalyzer.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import com.example.coderiskanalyzer.production.model.*;
import com.example.coderiskanalyzer.production.parser.JavaSourceParser;
import com.example.coderiskanalyzer.production.source.SourceFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProgramModelBuilderTests {

    @TempDir
    Path tempDir;

    @Test
    void buildsTypeFieldsMethodsAnnotationsAndCalls() throws Exception {
        Path root = tempDir.resolve("src/main/java");
        Files.createDirectories(root);
        Path source = root.resolve("Service.java");
        Files.writeString(source, "package sample; @Service class Service extends BaseService implements AutoCloseable { "
                + "private int count; @Transactional @Async @GetMapping void save(@RequestBody String id) throws Exception { "
                + "repository.persist(id); this.count++; } }");
        var parsed = new JavaSourceParser().parse(java.util.List.of(new SourceFile(root, source)));

        ProjectModel model = new ProgramModelBuilder().build(parsed);

        assertThat(model.classes()).hasSize(1);
        ClassInfo type = model.classes().getFirst();
        assertThat(type.fullyQualifiedName()).isEqualTo("sample.Service");
        assertThat(type.fields()).extracting(FieldInfo::name).contains("count");
        assertThat(type.superClass()).isEqualTo("BaseService");
        assertThat(type.interfaces()).containsExactly("AutoCloseable");
        assertThat(type.interfaceType()).isFalse();
        assertThat(type.methods()).extracting(MethodInfo::name).contains("save");
        assertThat(type.methods().getFirst().annotations()).contains("Transactional");
        assertThat(type.methods().getFirst().methodCalls()).contains("persist");
        assertThat(type.methods().getFirst().isHttpEntryPoint()).isTrue();
        assertThat(type.methods().getFirst().isAsync()).isTrue();
        assertThat(type.methods().getFirst().isTransactional()).isTrue();
        assertThat(type.methods().getFirst().parameters()).singleElement().satisfies(parameter -> {
            assertThat(parameter.name()).isEqualTo("id");
            assertThat(parameter.annotations()).contains("RequestBody");
        });
        assertThat(type.methods().getFirst().throwsTypes()).containsExactly("Exception");
        assertThat(type.methods().getFirst().fieldAccesses()).contains("this.count");
        assertThat(type.methods().getFirst().callDetails()).singleElement().satisfies(call -> {
            assertThat(call.name()).isEqualTo("persist");
            assertThat(call.scope()).isEqualTo("repository");
        });
    }
}
