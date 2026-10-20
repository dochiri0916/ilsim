package io.github.dochiri0916.buildconvention.validator.architecture

import io.github.dochiri0916.buildconvention.extension.HexagonalConventionExtension
import javax.tools.JavaCompiler
import javax.tools.ToolProvider
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ArchUnitArchitectureValidatorTest {

    @TempDir
    File tempDir

    @Test
    @DisplayName('읽지 못한 클래스 파일이 있으면 검증을 통과시키지 않는다')
    void rejectsClassFilesArchUnitCannotRead() {
        Project project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        File classFile = new File(tempDir, 'classes/com/example/order/domain/Order.class')
        classFile.parentFile.mkdirs()
        classFile.bytes = [0xCA, 0xFE, 0xBA, 0xBE, 0x00, 0x00, 0x00, 0x7F, 0x00, 0x01] as byte[]

        List<String> violations = ArchUnitArchitectureValidator.validate(
                project,
                new HexagonalConventionExtension(),
                [new File(tempDir, 'classes')]
        )

        assert violations.any { it.contains('could not read compiled classes') && it.contains('com.example.order.domain.Order') }
    }

    @Test
    @DisplayName('컴파일된 Application 클래스의 Adapter 의존을 거부한다')
    void rejectsCompiledApplicationDependencyOnAdapter() {
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

        List<String> violations = ArchUnitArchitectureValidator.validate(
                project,
                new HexagonalConventionExtension(),
                [classDir]
        )

        assert violations.any { it.contains('application must not depend on adapters') }
    }

    @Test
    @DisplayName('컴파일된 Application과 Domain의 Jackson 의존을 거부한다')
    void rejectsCompiledJacksonDependencyInApplicationAndDomain() {
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

        List<String> violations = ArchUnitArchitectureValidator.validate(
                project,
                new HexagonalConventionExtension(),
                [classDir]
        )

        assert violations.any { it.contains('application must not depend on technical framework types') }
        assert violations.any { it.contains('domain must stay framework independent') }
    }

    @Test
    @DisplayName('컴파일된 Application Port의 Spring Data 타입 의존을 거부한다')
    void rejectsCompiledApplicationDependencyOnTechnicalFramework() {
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

        List<String> violations = ArchUnitArchitectureValidator.validate(
                project,
                new HexagonalConventionExtension(),
                [classDir]
        )

        assert violations.any { it.contains('application must not depend on technical framework types') }
    }

    @Test
    @DisplayName('컴파일된 클래스 디렉터리가 없으면 위반이 없다')
    void accepts_project_without_compiled_classes() {
        Project project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        File regularFile = new File(tempDir, 'not-a-directory')
        regularFile.text = 'x'

        List<String> violations = ArchUnitArchitectureValidator.validate(
                project,
                new HexagonalConventionExtension(),
                [new File(tempDir, 'missing'), regularFile]
        )

        assert violations.empty
    }

    @Test
    @DisplayName('컴파일된 Adapter의 Application Service 의존을 거부한다')
    void rejectsCompiledAdapterDependencyOnApplicationService() {
        Project project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        File serviceSource = writeSource('com/example/order/application/service/OrderService.java', '''
                package com.example.order.application.service;

                public final class OrderService {
                }
                ''')
        File inboundSource = writeSource('com/example/order/adapter/in/scheduling/OrderScheduler.java', '''
                package com.example.order.adapter.in.scheduling;

                import com.example.order.application.service.OrderService;

                public final class OrderScheduler {
                    private final OrderService orderService = new OrderService();
                }
                ''')
        File outboundSource = writeSource('com/example/order/adapter/out/persistence/OrderPersistenceAdapter.java', '''
                package com.example.order.adapter.out.persistence;

                import com.example.order.application.service.OrderService;

                public final class OrderPersistenceAdapter {
                    private final OrderService orderService = new OrderService();
                }
                ''')
        File classDir = compile([serviceSource, inboundSource, outboundSource])

        List<String> violations = ArchUnitArchitectureValidator.validate(
                project,
                new HexagonalConventionExtension(),
                [classDir]
        )

        List<String> matched = violations.findAll { it.contains('adapters must depend on ports instead of application services') }
        assert matched.any { it.contains('OrderScheduler') }
        assert matched.any { it.contains('OrderPersistenceAdapter') }
    }

    @Test
    @DisplayName('컴파일된 Adapter의 Port 의존은 허용한다')
    void acceptsCompiledAdapterDependencyOnPorts() {
        Project project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        File useCaseSource = writeSource('com/example/order/application/port/in/usecase/PlaceOrderUseCase.java', '''
                package com.example.order.application.port.in.usecase;

                public interface PlaceOrderUseCase {
                }
                ''')
        File portSource = writeSource('com/example/order/application/port/out/OrderRepository.java', '''
                package com.example.order.application.port.out;

                public interface OrderRepository {
                }
                ''')
        File inboundSource = writeSource('com/example/order/adapter/in/scheduling/OrderScheduler.java', '''
                package com.example.order.adapter.in.scheduling;

                import com.example.order.application.port.in.usecase.PlaceOrderUseCase;

                public final class OrderScheduler {
                    private PlaceOrderUseCase placeOrderUseCase;
                }
                ''')
        File outboundSource = writeSource('com/example/order/adapter/out/persistence/OrderPersistenceAdapter.java', '''
                package com.example.order.adapter.out.persistence;

                import com.example.order.application.port.out.OrderRepository;

                public final class OrderPersistenceAdapter implements OrderRepository {
                }
                ''')
        File classDir = compile([useCaseSource, portSource, inboundSource, outboundSource])

        List<String> violations = ArchUnitArchitectureValidator.validate(
                project,
                new HexagonalConventionExtension(),
                [classDir]
        )

        assert violations.empty
    }

    @Test
    @DisplayName('컴파일된 Domain이 프로젝트 밖의 허용되지 않은 타입에 의존하면 거부한다')
    void rejectsCompiledDomainDependencyOnUnlistedExternalType() {
        Project project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        File libraryDir = compile([writeSource('org/apache/commons/lang3/StringUtils.java', '''
                package org.apache.commons.lang3;

                public final class StringUtils {
                    public static boolean isBlank(String value) {
                        return value == null || value.isBlank();
                    }
                }
                ''')], 'library')
        File domainSource = writeSource('com/example/order/domain/Order.java', '''
                package com.example.order.domain;

                import org.apache.commons.lang3.StringUtils;

                public final class Order {
                    public boolean unnamed(String name) {
                        return StringUtils.isBlank(name);
                    }
                }
                ''')
        File classDir = compile([domainSource], 'classes', libraryDir)

        List<String> violations = ArchUnitArchitectureValidator.validate(
                project,
                new HexagonalConventionExtension(),
                [classDir]
        )

        assert violations.any {
            it.contains('domain must depend only on the language standard library and project types') &&
                    it.contains('org.apache.commons.lang3.StringUtils')
        }
    }

    @Test
    @DisplayName('컴파일된 Domain의 표준 라이브러리·프로젝트 타입·Published Language 의존은 허용한다')
    void acceptsCompiledDomainDependencyOnStandardLibraryAndProjectTypes() {
        Project project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        File libraryDir = compile([
                writeSource('com/example/shared/kernel/Money.java', '''
                        package com.example.shared.kernel;

                        public final class Money {
                        }
                        '''),
                writeSource('com/example/contract/payment/PaymentReference.java', '''
                        package com.example.contract.payment;

                        public final class PaymentReference {
                        }
                        '''),
                writeSource('com/github/f4b6a3/uuid/UuidCreator.java', '''
                        package com.github.f4b6a3.uuid;

                        import java.util.UUID;

                        public final class UuidCreator {
                            public static UUID getTimeOrderedEpoch() {
                                return UUID.randomUUID();
                            }
                        }
                        ''')
        ], 'library')
        File valueSource = writeSource('com/example/order/domain/vo/OrderId.java', '''
                package com.example.order.domain.vo;

                import com.github.f4b6a3.uuid.UuidCreator;
                import java.util.UUID;

                public final class OrderId {
                    private final UUID value = UuidCreator.getTimeOrderedEpoch();
                }
                ''')
        File domainSource = writeSource('com/example/order/domain/Order.java', '''
                package com.example.order.domain;

                import com.example.contract.payment.PaymentReference;
                import com.example.order.domain.vo.OrderId;
                import com.example.shared.kernel.Money;
                import java.util.List;

                public final class Order {
                    private OrderId id;
                    private Money total;
                    private Money[][] totals;
                    private PaymentReference payment;
                    private PaymentReference[] payments;
                    private List<String> lines = List.of();
                }
                ''')
        File classDir = compile([valueSource, domainSource], 'classes', libraryDir)
        HexagonalConventionExtension convention = new HexagonalConventionExtension()
        convention.basePackage = 'com.example.shared'
        convention.publishedLanguagePackagePrefixes = ['com.example.contract.'] as Set

        List<String> violations = ArchUnitArchitectureValidator.validate(project, convention, [classDir])

        assert violations.empty
    }

    @Test
    @DisplayName('컴파일된 Application과 Domain의 Jackson 3 의존을 거부한다')
    void rejectsCompiledJackson3DependencyInApplicationAndDomain() {
        Project project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        File jacksonSource = writeSource('tools/jackson/databind/ObjectMapper.java', '''
                package tools.jackson.databind;

                public class ObjectMapper {
                }
                ''')
        File applicationSource = writeSource('com/example/order/application/service/OrderService.java', '''
                package com.example.order.application.service;

                import tools.jackson.databind.ObjectMapper;

                public final class OrderService {
                    private final ObjectMapper mapper = new ObjectMapper();
                }
                ''')
        File domainSource = writeSource('com/example/order/domain/Order.java', '''
                package com.example.order.domain;

                import tools.jackson.databind.ObjectMapper;

                public final class Order {
                    private final ObjectMapper mapper = new ObjectMapper();
                }
                ''')
        File classDir = compile([jacksonSource, applicationSource, domainSource])

        List<String> violations = ArchUnitArchitectureValidator.validate(
                project,
                new HexagonalConventionExtension(),
                [classDir]
        )

        assert violations.any { it.contains('application must not depend on technical framework types') && it.contains('tools.jackson') }
        assert violations.any { it.contains('domain must stay framework independent') && it.contains('tools.jackson') }
    }

    @Test
    @DisplayName('도메인 enum의 합성 배열과 내부·표준·기본 타입의 다차원 배열을 허용한다')
    void acceptsDomainEnumAndInternalArrays() {
        Project project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        File state = writeSource('com/example/order/domain/vo/OrderStatus.java', '''
                package com.example.order.domain.vo;
                public enum OrderStatus { PENDING, COMPLETED }
                ''')
        File order = writeSource('com/example/order/domain/Order.java', '''
                package com.example.order.domain;
                import com.example.order.domain.vo.OrderStatus;
                import java.util.UUID;
                public final class Order {
                    private OrderStatus[][] history;
                    private UUID[] ids;
                    private String[][] names;
                    private int[] quantities;
                }
                ''')
        File classes = compile([state, order])
        assert ArchUnitArchitectureValidator.validate(project, new HexagonalConventionExtension(), [classes]).isEmpty()
    }

    @Test
    @DisplayName('금지된 외부 타입을 다차원 배열로 감싸도 도메인 의존 위반으로 거절한다')
    void rejectsExternalArrayDependencies() {
        Project project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        File library = compile([writeSource('third/party/External.java', '''
                package third.party;
                public final class External { }
                ''')], 'library')
        File domain = writeSource('com/example/order/domain/Order.java', '''
                package com.example.order.domain;
                import third.party.External;
                public final class Order { private External[][] values; }
                ''')
        File classes = compile([domain], 'classes', library)
        List<String> violations = ArchUnitArchitectureValidator.validate(project, new HexagonalConventionExtension(), [classes])
        assert violations.any { it.contains('domain must depend only') && it.contains('third.party.External') }
    }

    private File compile(List<File> sources, String outputName = 'classes', File classpath = null) {
        File classDir = new File(tempDir, outputName)
        classDir.mkdirs()
        List<String> arguments = ['-d', classDir.absolutePath]
        if (classpath != null) {
            arguments.addAll(['-cp', classpath.absolutePath])
        }
        arguments.addAll(sources.collect { File source -> source.absolutePath })
        assert ToolProvider.systemJavaCompiler.run(null, null, null, arguments as String[]) == 0
        return classDir
    }

    private File writeSource(String path, String source) {
        File file = new File(tempDir, "sources/${path}")
        file.parentFile.mkdirs()
        file.text = source.stripIndent().trim() + System.lineSeparator()
        return file
    }
}
