package com.dochiri.convention.validator

import com.dochiri.convention.extension.HexagonalConventionExtension
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

    private File writeSource(String path, String source) {
        File file = new File(tempDir, "sources/${path}")
        file.parentFile.mkdirs()
        file.text = source.stripIndent().trim() + System.lineSeparator()
        return file
    }
}
