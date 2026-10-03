package io.github.dochiri0916.buildconvention.validator

import io.github.dochiri0916.buildconvention.extension.HexagonalConventionExtension
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PackageTopologyConventionValidatorTest {

    @TempDir
    File tempDir

    @Test
    @DisplayName('부트스트랩과 명시적 base package가 없으면 패키지 검증에 실패한다')
    void rejectsProjectWithoutApplicationRoot() {
        // given
        Project project = sampleProject('missing-root')
        writeJava(project, 'com/example/order/domain/model/Order.java', '''
                package com.example.order.domain.model;

                public record Order() {
                }
                ''')

        // when
        List<String> violations = PackageTopologyConventionValidator.validate(
                project,
                new HexagonalConventionExtension()
        )

        // then
        assert violations.any { it.contains('base package could not be determined') }
    }

    @Test
    @DisplayName('명시한 base package로 부트스트랩 없는 모듈을 검증한다')
    void acceptsExplicitBasePackageWithoutBootstrap() {
        // given
        Project project = sampleProject('explicit-root')
        writeJava(project, 'com/example/order/domain/model/Order.java', '''
                package com.example.order.domain.model;

                public record Order() {
                }
                ''')
        HexagonalConventionExtension convention = new HexagonalConventionExtension()
        convention.basePackage = 'com.example'

        // when
        List<String> violations = PackageTopologyConventionValidator.validate(project, convention)

        // then
        assert !violations.any { it.contains('base package could not be determined') }
        assert !violations.any { it.contains('must be under application root package') }
    }

    @Test
    @DisplayName('base package에 레이어 이름이 있으면 레이어 판별이 모호하므로 거부한다')
    void rejectsRootPackageContainingLayerSegment() {
        // given
        Project project = sampleProject('ambiguous-root')
        writeJava(project, 'com/domain/order/domain/model/Order.java', '''
                package com.domain.order.domain.model;

                public record Order() {
                }
                ''')
        HexagonalConventionExtension convention = new HexagonalConventionExtension()
        convention.basePackage = 'com.domain'

        // when
        List<String> violations = PackageTopologyConventionValidator.validate(project, convention)

        // then
        assert violations.any { it.contains("must not contain layer segment 'domain'") }
    }

    @Test
    @DisplayName('빈 source 프로젝트는 package root 없이 허용한다')
    void acceptsProjectWithoutMainJavaSources() {
        // given
        Project project = sampleProject('empty-project')

        // when
        List<String> violations = PackageTopologyConventionValidator.validate(project)

        // then
        assert violations.isEmpty()
    }

    @Test
    @DisplayName('Context 우선 package topology의 모든 잘못된 분기를 진단한다')
    void rejectsInvalidContextFirstTopologyBranches() {
        // given
        Project project = sampleProject('topology-matrix')
        writeJava(project, 'com/example/TestApplication.java', '''
                package com.example;
                public class TestApplication {
                }
                ''')
        writeType(project, 'com/other/Outside.java', 'com.other', 'Outside')
        writeType(project, 'com/example/Helper.java', 'com.example', 'Helper')
        writeType(project, 'com/example/global/security/Auth.java', 'com.example.global.security', 'Auth')
        writeType(project, 'com/example/domain/order/Order.java', 'com.example.domain.order', 'Order')
        writeType(project, 'com/example/common/domain/model/Common.java', 'com.example.common.domain.model', 'Common')
        writeType(project, 'com/example/order/Root.java', 'com.example.order', 'Root')
        writeType(project, 'com/example/order/feature/Feature.java', 'com.example.order.feature', 'Feature')
        writeType(project, 'com/example/order/domain/DomainRoot.java', 'com.example.order.domain', 'DomainRoot')
        writeType(project, 'com/example/order/domain/vo/Money.java', 'com.example.order.domain.vo', 'Money')
        writeType(project, 'com/example/order/domain/value/Amount.java', 'com.example.order.domain.value', 'Amount')
        writeType(project, 'com/example/order/application/ApplicationRoot.java', 'com.example.order.application', 'ApplicationRoot')
        writeType(project, 'com/example/order/application/usecase/Place.java', 'com.example.order.application.usecase', 'Place')
        writeType(project, 'com/example/order/application/port/PortRoot.java', 'com.example.order.application.port', 'PortRoot')
        writeType(project, 'com/example/order/application/port/input/Input.java', 'com.example.order.application.port.input', 'Input')
        writeType(project, 'com/example/order/adapter/AdapterRoot.java', 'com.example.order.adapter', 'AdapterRoot')
        writeType(project, 'com/example/order/adapter/persistence/Persistence.java', 'com.example.order.adapter.persistence', 'Persistence')
        writeType(project, 'com/example/order/adapter/in/InboundRoot.java', 'com.example.order.adapter.in', 'InboundRoot')
        writeType(project, 'com/example/order/application/port/in/PortInRoot.java', 'com.example.order.application.port.in', 'PortInRoot')
        writeType(project, 'com/example/order/application/port/in/dto/InDto.java', 'com.example.order.application.port.in.dto', 'InDto')
        writeType(project, 'com/example/order/application/port/in/usecase/PlaceUseCase.java', 'com.example.order.application.port.in.usecase', 'PlaceUseCase')
        writeType(project, 'com/example/order/application/port/in/command/PlaceCommand.java', 'com.example.order.application.port.in.command', 'PlaceCommand')
        writeType(project, 'com/example/order/application/port/in/query/FindQuery.java', 'com.example.order.application.port.in.query', 'FindQuery')
        writeType(project, 'com/example/order/application/port/in/result/PlaceResult.java', 'com.example.order.application.port.in.result', 'PlaceResult')
        writeType(project, 'com/example/order/adapter/in/web/mapper/WebMapper.java', 'com.example.order.adapter.in.web.mapper', 'WebMapper')
        writeType(project, 'com/example/order/adapter/in/web/request/deep/DeepRequest.java', 'com.example.order.adapter.in.web.request.deep', 'DeepRequest')
        writeType(project, 'com/example/order/adapter/in/web/OrderController.java', 'com.example.order.adapter.in.web', 'OrderController')
        writeType(project, 'com/example/order/adapter/in/web/request/PlaceRequest.java', 'com.example.order.adapter.in.web.request', 'PlaceRequest')
        writeType(project, 'com/example/order/adapter/in/web/response/PlaceResponse.java', 'com.example.order.adapter.in.web.response', 'PlaceResponse')
        writeType(project, 'com/example/order/adapter/in/grpc/Grpc.java', 'com.example.order.adapter.in.grpc', 'Grpc')
        writeType(project, 'com/example/global/configuration/AppConfig.java', 'com.example.global.configuration', 'AppConfig')
        writeType(project, 'com/example/global/configuration/jpa/JpaConfig.java', 'com.example.global.configuration.jpa', 'JpaConfig')
        writeType(project, 'com/example/global/exception/ApiError.java', 'com.example.global.exception', 'ApiError')
        writeType(project, 'com/example/global/persistence/JpaAuditingConfig.java', 'com.example.global.persistence', 'JpaAuditingConfig')
        writeType(project, 'com/example/global/persistence/auditing/BaseEntity.java', 'com.example.global.persistence.auditing', 'BaseEntity')
        writeType(project, 'com/example/global/configuration/properties/AppProperties.java', 'com.example.global.configuration.properties', 'AppProperties')

        // when
        List<String> violations = PackageTopologyConventionValidator.validate(
                project,
                new HexagonalConventionExtension()
        )

        // then
        assert violations.any { it.contains("must be under application root package 'com.example'") }
        assert violations.any { it.contains('root package may contain only the application bootstrap class') }
        assert violations.any { it.contains('global package must be limited to global.exception, global.web, global.persistence, or global.configuration.properties') }
        assert violations.any { it.contains('package must be context-first') }
        assert violations.any { it.contains("package 'common' is not a bounded context") }
        assert violations.any { it.contains("bounded context package 'order' must contain") }
        assert violations.any { it.contains('package must follow {context}/domain') }
        assert violations.any { it.contains('order.domain package must declare a valid child package') }
        assert violations.any { it.contains('order.domain package must use model, vo, event, or exception') }
        assert violations.any { it.contains('order.application package must declare a valid child package') }
        assert violations.any { it.contains('order.application package must use port, exception, or service') }
        assert violations.any { it.contains('order.application.port package must use in or out') }
        assert violations.any { it.contains('order.adapter package must declare a valid child package') }
        assert violations.any { it.contains('order.adapter package must use in or out') }
        assert violations.any { it.contains('order.adapter.in package must use bootstrap, event, messaging, scheduler, or web') }
        assert violations.any { it.contains('AppConfig.java') && it.contains('global package must be limited') }
        assert violations.any { it.contains('JpaConfig.java') && it.contains('global package must be limited') }
        assert violations.any { it.contains('PortInRoot.java') && it.contains('port.in package must use usecase, command, query, or result') }
        assert violations.any { it.contains('InDto.java') && it.contains('port.in package must use usecase, command, query, or result') }
        assert violations.any { it.contains('WebMapper.java') && it.contains('adapter.in.web package must use request or response') }
        assert violations.any { it.contains('DeepRequest.java') && it.contains('adapter.in.web package must use request or response') }
        ['PlaceUseCase', 'PlaceCommand', 'FindQuery', 'PlaceResult', 'OrderController', 'PlaceRequest', 'PlaceResponse'].each { String name ->
            assert !violations.any { it.contains("${name}.java") }
        }
        assert !violations.any { it.contains('Money.java') }
        assert !violations.any { it.contains('ApiError.java') }
        assert !violations.any { it.contains('JpaAuditingConfig.java') }
        assert !violations.any { it.contains('BaseEntity.java') }
        assert !violations.any { it.contains('AppProperties.java') }
    }

    @Test
    @DisplayName('줄바꿈된 package에서도 잘못된 Domain child를 AST로 거부한다')
    void rejectsInvalidMultilinePackageTopology() {
        // given
        Project project = sampleProject('multiline-package')
        writeJava(project, 'com/example/TestApplication.java', '''
                package com.example;
                public class TestApplication {
                }
                ''')
        writeJava(project, 'com/example/order/domain/value/Amount.java', '''
                package com.example.order
                    .domain.value;

                public record Amount(int value) {
                }
                ''')

        // when
        List<String> violations = PackageTopologyConventionValidator.validate(
                project,
                new HexagonalConventionExtension()
        )

        // then
        assert violations.any { it.contains('order.domain package must use model, vo, event, or exception') }
    }

    @Test
    @DisplayName('줄바꿈된 bootstrap package에서도 application root를 AST로 결정한다')
    void resolvesApplicationRootFromMultilinePackage() {
        // given
        Project project = sampleProject('multiline-bootstrap')
        writeJava(project, 'com/example/TestApplication.java', '''
                package com
                    .example;

                public class TestApplication {
                }
                ''')
        writeJava(project, 'com/example/order/domain/model/Order.java', '''
                package com.example.order.domain.model;

                public record Order() {
                }
                ''')

        // when
        List<String> violations = PackageTopologyConventionValidator.validate(
                project,
                new HexagonalConventionExtension()
        )

        // then
        assert !violations.any { it.contains('application base package could not be determined') }
        assert !violations.any { it.contains('must be under application root package') }
    }

    private Project sampleProject(String name) {
        File projectDir = new File(tempDir, name)
        projectDir.mkdirs()
        return ProjectBuilder.builder().withProjectDir(projectDir).build()
    }

    private static void writeJava(Project project, String path, String source) {
        File file = new File(project.projectDir, "src/main/java/${path}")
        file.parentFile.mkdirs()
        file.text = source.stripIndent().trim() + System.lineSeparator()
    }

    private static void writeType(Project project, String path, String packageName, String typeName) {
        writeJava(project, path, """
                package ${packageName};
                public final class ${typeName} {
                }
                """)
    }
}
