package io.github.dochiri0916.buildconvention.validator

import io.github.dochiri0916.buildconvention.extension.HexagonalConventionExtension
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import javax.tools.JavaCompiler
import javax.tools.ToolProvider

class ArchUnitArchitectureValidatorTest {

    @TempDir
    File tempDir

    @Test
    @DisplayName('읽지 못한 클래스 파일이 있으면 검증을 통과시키지 않는다')
    void rejectsClassFilesArchUnitCannotRead() {
        // given: a class file from a newer Java version than the bundled bytecode reader supports
        Project project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        File classFile = new File(tempDir, 'classes/com/example/order/domain/Order.class')
        classFile.parentFile.mkdirs()
        classFile.bytes = [0xCA, 0xFE, 0xBA, 0xBE, 0x00, 0x00, 0x00, 0x7F, 0x00, 0x01] as byte[]

        // when
        List<String> violations = ArchUnitArchitectureValidator.validate(
                project,
                new HexagonalConventionExtension(),
                [new File(tempDir, 'classes')]
        )

        // then
        assert violations.any { it.contains('could not read compiled classes') && it.contains('com.example.order.domain.Order') }
    }

    @Test
    @DisplayName('컴파일된 Application 클래스의 Adapter 의존을 거부한다')
    void rejectsCompiledApplicationDependencyOnAdapter() {
        // given
        Project project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        File adapterSource = writeSource('com/example/order/adapter/out/OrderAdapter.java', '''
                package com.example.order.adapter.out;

                public final class OrderAdapter {
                }
                ''')
        File serviceSource = writeSource('com/example/order/application/service/OrderService.java', '''
                package com.example.order.application.service;

                import com.example.order.adapter.out.OrderAdapter;

                public final class OrderService {
                    private final OrderAdapter orderAdapter;

                    public OrderService(final OrderAdapter orderAdapter) {
                        this.orderAdapter = orderAdapter;
                    }
                }
                ''')
        File classDir = new File(tempDir, 'classes')
        classDir.mkdirs()
        JavaCompiler compiler = ToolProvider.systemJavaCompiler
        int compilationResult = compiler.run(
                null,
                null,
                null,
                '-d',
                classDir.absolutePath,
                adapterSource.absolutePath,
                serviceSource.absolutePath
        )
        assert compilationResult == 0

        // when
        List<String> violations = ArchUnitArchitectureValidator.validate(
                project,
                new HexagonalConventionExtension(),
                [classDir]
        )

        // then
        assert violations.any { it.contains('application must not depend on adapters') }
    }

    @Test
    @DisplayName('컴파일된 Application과 Domain의 Jackson 의존을 거부한다')
    void rejectsCompiledJacksonDependencyInApplicationAndDomain() {
        // given
        Project project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        File jacksonSource = writeSource('com/fasterxml/jackson/databind/ObjectMapper.java', '''
                package com.fasterxml.jackson.databind;

                public class ObjectMapper {
                }
                ''')
        File applicationSource = writeSource('com/example/order/application/service/OrderService.java', '''
                package com.example.order.application.service;

                import com.fasterxml.jackson.databind.ObjectMapper;

                public final class OrderService {
                    private final ObjectMapper mapper = new ObjectMapper();
                }
                ''')
        File domainSource = writeSource('com/example/order/domain/model/Order.java', '''
                package com.example.order.domain.model;

                import com.fasterxml.jackson.databind.ObjectMapper;

                public final class Order {
                    private final ObjectMapper mapper = new ObjectMapper();
                }
                ''')
        File classDir = new File(tempDir, 'classes')
        classDir.mkdirs()
        int compilationResult = ToolProvider.systemJavaCompiler.run(
                null, null, null, '-d', classDir.absolutePath,
                jacksonSource.absolutePath, applicationSource.absolutePath, domainSource.absolutePath
        )
        assert compilationResult == 0

        // when
        List<String> violations = ArchUnitArchitectureValidator.validate(
                project,
                new HexagonalConventionExtension(),
                [classDir]
        )

        // then
        assert violations.any { it.contains('application must not depend on technical framework types') }
        assert violations.any { it.contains('domain must stay framework independent') }
    }

    @Test
    @DisplayName('컴파일된 Application Port의 Spring Data 타입 의존을 거부한다')
    void rejectsCompiledApplicationDependencyOnTechnicalFramework() {
        // given
        Project project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        File pageSource = writeSource('org/springframework/data/domain/Page.java', '''
                package org.springframework.data.domain;

                public interface Page<T> {
                }
                ''')
        File portSource = writeSource('com/example/order/application/port/out/OrderQueryPort.java', '''
                package com.example.order.application.port.out;

                import org.springframework.data.domain.Page;

                public interface OrderQueryPort {
                    Page<String> findAll();
                }
                ''')
        File classDir = new File(tempDir, 'technical-classes')
        classDir.mkdirs()
        JavaCompiler compiler = ToolProvider.systemJavaCompiler
        int compilationResult = compiler.run(
                null,
                null,
                null,
                '-d',
                classDir.absolutePath,
                pageSource.absolutePath,
                portSource.absolutePath
        )
        assert compilationResult == 0

        // when
        List<String> violations = ArchUnitArchitectureValidator.validate(
                project,
                new HexagonalConventionExtension(),
                [classDir]
        )

        // then
        assert violations.any { it.contains('application must not depend on technical framework types') }
    }

    @Test
    @DisplayName('컴파일된 클래스 디렉터리가 없으면 위반이 없다')
    void accepts_project_without_compiled_classes() {
        // given
        Project project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        File regularFile = new File(tempDir, 'not-a-directory')
        regularFile.text = 'x'

        // when
        List<String> violations = ArchUnitArchitectureValidator.validate(
                project,
                new HexagonalConventionExtension(),
                [new File(tempDir, 'missing'), regularFile]
        )

        // then
        assert violations.empty
    }

    private File writeSource(String path, String source) {
        File file = new File(tempDir, "sources/${path}")
        file.parentFile.mkdirs()
        file.text = source.stripIndent().trim() + System.lineSeparator()
        return file
    }
}
