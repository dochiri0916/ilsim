package com.dochiri.convention.validator

import com.dochiri.convention.extension.HexagonalConventionExtension
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AggregateBoundaryConventionValidatorTest {

    @TempDir
    File tempDir

    @Test
    @DisplayName('같은 Context의 다른 Aggregate Root는 식별자 VO 없이 직접 보관할 수 없다')
    void rejectsDirectAggregateRootReferenceInsideSameContext() {
        // given
        Project project = sampleProject('same-context-reference')
        writeApplication(project)
        writeAggregate(project, 'Order')
        writeAggregate(project, 'Cart')
        writeRepositoryPort(project, 'OrderRepositoryPort', 'Order')
        writeRepositoryPort(project, 'CartRepositoryPort', 'Cart')
        writeJava(project, 'com/example/sales/domain/model/Checkout.java', '''
                package com.example.sales.domain.model;

                public record Checkout(Cart cart) {

                    public Checkout {
                        if (cart == null) {
                            throw new IllegalArgumentException();
                        }
                    }
                }
                ''')

        // when
        List<String> violations = JavaSourceArchitectureValidator.validate(
                project,
                new HexagonalConventionExtension()
        )

        // then
        assert violations.any {
            it.contains("domain must reference aggregate root 'Cart' through an identifier VO")
        }
    }

    @Test
    @DisplayName('두 Repository Port가 같은 Aggregate Root를 저장하면 다중 Aggregate 변경으로 보지 않는다')
    void acceptsTwoRepositoryPortsBoundToSameAggregateRoot() {
        // given
        Project project = sampleProject('same-aggregate-repositories')
        writeApplication(project)
        writeAggregate(project, 'Order')
        writeRepositoryPort(project, 'OrderRepositoryPort', 'Order')
        writeRepositoryPort(project, 'OrderArchiveRepositoryPort', 'Order')
        writeJava(project, 'com/example/sales/application/port/in/ArchiveOrderUseCase.java', '''
                package com.example.sales.application.port.in;

                public interface ArchiveOrderUseCase {
                    void archive();
                }
                ''')
        writeJava(project, 'com/example/sales/application/service/ArchiveOrderService.java', '''
                package com.example.sales.application.service;

                import com.example.sales.application.port.in.ArchiveOrderUseCase;
                import com.example.sales.application.port.out.OrderArchiveRepositoryPort;
                import com.example.sales.application.port.out.OrderRepositoryPort;
                import lombok.RequiredArgsConstructor;
                import org.springframework.stereotype.Service;
                import org.springframework.transaction.annotation.Transactional;

                @Service
                @RequiredArgsConstructor
                public final class ArchiveOrderService implements ArchiveOrderUseCase {

                    private final OrderRepositoryPort orderRepositoryPort;
                    private final OrderArchiveRepositoryPort orderArchiveRepositoryPort;

                    @Override
                    @Transactional
                    public void archive() {
                        orderRepositoryPort.save(null);
                        orderArchiveRepositoryPort.save(null);
                    }
                }
                ''')

        // when
        List<String> violations = JavaSourceArchitectureValidator.validate(
                project,
                new HexagonalConventionExtension()
        )

        // then
        assert !violations.any {
            it.contains("application service method 'archive' must not modify multiple aggregate repositories")
        }
    }

    @Test
    @DisplayName('하나의 Repository Port가 여러 Aggregate Root를 저장하면 모호한 경계로 거부한다')
    void rejectsRepositoryPortBoundToMultipleAggregateRoots() {
        // given
        Project project = sampleProject('ambiguous-repository')
        writeApplication(project)
        writeAggregate(project, 'Order')
        writeAggregate(project, 'Cart')
        writeJava(project, 'com/example/sales/application/port/out/CombinedRepositoryPort.java', '''
                package com.example.sales.application.port.out;

                import com.example.sales.domain.model.Cart;
                import com.example.sales.domain.model.Order;

                public interface CombinedRepositoryPort {
                    void save(Order order);
                    void save(Cart cart);
                }
                ''')

        // when
        List<String> violations = JavaSourceArchitectureValidator.validate(
                project,
                new HexagonalConventionExtension()
        )

        // then
        assert violations.any {
            it.contains("repository port 'CombinedRepositoryPort' must manage exactly one aggregate root")
                    && it.contains('Cart, Order')
        }
    }

    @Test
    @DisplayName('Aggregate Root가 시그니처에 없는 Repository Port는 경계를 결정할 수 없어 거부한다')
    void rejectsRepositoryPortWithoutAggregateRootType() {
        // given
        Project project = sampleProject('unbound-repository')
        writeApplication(project)
        writeJava(project, 'com/example/sales/application/port/out/EmailRepositoryPort.java', '''
                package com.example.sales.application.port.out;

                public interface EmailRepositoryPort {
                    boolean existsByEmail(String email);
                }
                ''')

        // when
        List<String> violations = JavaSourceArchitectureValidator.validate(
                project,
                new HexagonalConventionExtension()
        )

        // then
        assert violations.any {
            it.contains("repository port 'EmailRepositoryPort' must expose one aggregate root")
        }
    }

    @Test
    @DisplayName('Java 문법을 파싱할 수 없으면 검증을 통과시키지 않는다')
    void failsClosedWhenJavaSourceCannotBeParsed() {
        // given
        Project project = sampleProject('invalid-java')
        writeApplication(project)
        writeJava(project, 'com/example/sales/domain/model/BrokenOrder.java', '''
                package com.example.sales.domain.model;

                public record BrokenOrder(String value) {
                ''')

        // when
        List<String> violations = JavaSourceArchitectureValidator.validate(
                project,
                new HexagonalConventionExtension()
        )

        // then
        assert violations.any {
            it.contains('BrokenOrder.java could not be parsed as Java source')
        }
    }

    @Test
    @DisplayName('Repository 타입은 FQCN import fallback과 동명 모호성을 구분해 Aggregate Root에 연결한다')
    void resolvesRepositoryTypeReferencesWithoutGuessingAmbiguousSimpleNames() {
        // given
        Project project = sampleProject('repository-resolution')
        writeApplication(project)
        writeAggregate(project, 'Order')
        writeAggregate(project, 'Cart')
        writeRepositoryPort(project, 'OrderRepositoryPort', 'Order')
        writeJava(project, 'com/example/sales/application/port/out/CombinedRepositoryPort.java', '''
                package com.example.sales.application.port.out;

                import com.example.sales.domain.model.Cart;
                import com.example.sales.domain.model.Order;

                public interface CombinedRepositoryPort {
                    void save(Order order);
                    void save(Cart cart);
                }
                ''')
        writeJava(project, 'com/example/sales/application/service/ResolverService.java', '''
                package com.example.sales.application.service;

                import com.example.sales.application.port.out.OrderRepositoryPort;

                public final class ResolverService {
                    private final OrderRepositoryPort repository;
                }
                ''')
        writeJava(project, 'com/example/billing/domain/model/Invoice.java', '''
                package com.example.billing.domain.model;

                public record Invoice(InvoiceId id) {
                }
                ''')
        writeJava(project, 'com/example/billing/domain/model/InvoiceId.java', '''
                package com.example.billing.domain.model;

                public record InvoiceId(String value) {
                }
                ''')
        writeJava(project, 'com/example/billing/application/port/out/OrderRepositoryPort.java', '''
                package com.example.billing.application.port.out;

                import com.example.billing.domain.model.Invoice;

                public interface OrderRepositoryPort {
                    void save(Invoice invoice);
                }
                ''')
        File serviceFile = project.file(
                'src/main/java/com/example/sales/application/service/ResolverService.java'
        )
        AggregateBoundaryConventionValidator.Analysis analysis =
                AggregateBoundaryConventionValidator.analyze(
                        project,
                        new HexagonalConventionExtension()
                )

        // when
        String fromQualifiedType = analysis.aggregateRootForRepository(
                serviceFile,
                'com.example.sales.application.port.out.OrderRepositoryPort[]'
        )
        String fromImportedType = analysis.aggregateRootForRepository(serviceFile, 'OrderRepositoryPort')
        String ambiguousRoot = analysis.aggregateRootForRepository(
                project.file('src/main/java/com/example/Unknown.java'),
                'OrderRepositoryPort'
        )
        String multipleRoots = analysis.aggregateRootForRepository(
                serviceFile,
                'com.example.sales.application.port.out.CombinedRepositoryPort'
        )
        String missingRoot = analysis.aggregateRootForRepository(serviceFile, 'MissingRepositoryPort')

        // then
        assert fromQualifiedType == 'com.example.sales.domain.model.Order'
        assert fromImportedType == 'com.example.sales.domain.model.Order'
        assert ambiguousRoot == null
        assert multipleRoots == null
        assert missingRoot == null
    }

    private Project sampleProject(String name) {
        File projectDir = new File(tempDir, name)
        projectDir.mkdirs()
        return ProjectBuilder.builder().withProjectDir(projectDir).build()
    }

    private static void writeApplication(Project project) {
        writeJava(project, 'com/example/TestApplication.java', '''
                package com.example;

                public class TestApplication {
                }
                ''')
    }

    private static void writeAggregate(Project project, String typeName) {
        writeJava(project, "com/example/sales/domain/model/${typeName}.java", """
                package com.example.sales.domain.model;

                public record ${typeName}(${typeName}Id id) {

                    public ${typeName} {
                        if (id == null) {
                            throw new IllegalArgumentException();
                        }
                    }

                    public static ${typeName} create(final ${typeName}Id id) {
                        return new ${typeName}(id);
                    }

                    @Override
                    public boolean equals(final Object other) {
                        return this == other
                                || other instanceof ${typeName} target && id.equals(target.id);
                    }

                    @Override
                    public int hashCode() {
                        return id.hashCode();
                    }
                }
                """)
        writeJava(project, "com/example/sales/domain/model/${typeName}Id.java", """
                package com.example.sales.domain.model;

                public record ${typeName}Id(String value) {

                    public ${typeName}Id {
                        if (value == null) {
                            throw new IllegalArgumentException();
                        }
                        if (value.isBlank()) {
                            throw new IllegalArgumentException();
                        }
                    }

                    public static ${typeName}Id generate() {
                        return new ${typeName}Id("${typeName.toLowerCase()}-id");
                    }
                }
                """)
    }

    private static void writeRepositoryPort(Project project, String portName, String aggregateName) {
        writeJava(project, "com/example/sales/application/port/out/${portName}.java", """
                package com.example.sales.application.port.out;

                import com.example.sales.domain.model.${aggregateName};

                public interface ${portName} {
                    void save(${aggregateName} aggregate);
                }
                """)
    }

    private static void writeJava(Project project, String path, String source) {
        File file = new File(project.projectDir, "src/main/java/${path}")
        file.parentFile.mkdirs()
        file.text = source.stripIndent().trim() + System.lineSeparator()
    }
}
