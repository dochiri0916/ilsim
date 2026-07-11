package com.dochiri.convention.validator

import com.dochiri.convention.extension.HexagonalConventionExtension
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DomainStaticFactoryValidatorTest {

    @TempDir
    File tempDir

    @Test
    @DisplayName('Domain class의 공개 생성자와 정적 팩토리 누락을 거부한다')
    void rejectsPublicConstructorWithoutStaticFactory() {
        // given
        Project project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        writeJava(project, 'com/example/order/domain/model/Order.java', '''
                package com.example.order.domain.model;

                public final class Order {

                    public Order() {
                    }
                }
                ''')

        // when
        List<String> violations = DomainStaticFactoryValidator.validate(
                project,
                new HexagonalConventionExtension()
        )

        // then
        assert violations.any { it.contains('must not expose public/protected constructor') }
        assert violations.any { it.contains('must declare a private constructor') }
        assert violations.any { it.contains("must declare at least one public static factory method returning 'Order'") }
    }

    @Test
    @DisplayName('정적 factory 검증이 비활성화되면 Domain class를 검사하지 않는다')
    void skipsValidationWhenConventionIsDisabled() {
        // given
        Project project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        writeJava(project, 'com/example/order/domain/model/Order.java', '''
                package com.example.order.domain.model;
                public class Order {
                }
                ''')
        HexagonalConventionExtension convention = new HexagonalConventionExtension()
        convention.enforceDomainStaticFactoryMethod = false

        // when
        List<String> violations = DomainStaticFactoryValidator.validate(project, convention)

        // then
        assert violations.isEmpty()
    }

    @Test
    @DisplayName('record enum interface 예외와 Domain Service는 factory 검사에서 제외한다')
    void skipsTypesThatDoNotUseAggregateFactoryConvention() {
        // given
        Project project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        writeJava(project, 'com/example/order/domain/model/Order.java', '''
                package com.example.order.domain.model;
                public record Order() {
                }
                ''')
        writeJava(project, 'com/example/order/domain/model/OrderStatus.java', '''
                package com.example.order.domain.model;
                public enum OrderStatus { CREATED }
                ''')
        writeJava(project, 'com/example/order/domain/model/OrderPolicy.java', '''
                package com.example.order.domain.model;
                public interface OrderPolicy {
                }
                ''')
        writeJava(project, 'com/example/order/domain/model/PricingService.java', '''
                package com.example.order.domain.model;
                public final class PricingService {
                }
                ''')
        writeJava(project, 'com/example/order/domain/exception/InvalidOrderException.java', '''
                package com.example.order.domain.exception;
                public final class InvalidOrderException extends RuntimeException {
                }
                ''')
        writeJava(project, 'com/example/order/adapter/out/OrderAdapter.java', '''
                package com.example.order.adapter.out;
                public class OrderAdapter {
                }
                ''')

        // when
        List<String> violations = DomainStaticFactoryValidator.validate(
                project,
                new HexagonalConventionExtension()
        )

        // then
        assert violations.isEmpty()
    }

    @Test
    @DisplayName('private Lombok constructor와 public static final factory를 허용한다')
    void acceptsLombokPrivateConstructorAndStaticFinalFactory() {
        // given
        Project project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        writeJava(project, 'com/example/order/domain/model/Order.java', '''
                package com.example.order.domain.model;

                import lombok.AccessLevel;
                import lombok.NoArgsConstructor;

                @NoArgsConstructor(access = AccessLevel.PRIVATE)
                public final class Order {
                    public static final Order create() {
                        return new Order();
                    }
                }
                ''')

        // when
        List<String> violations = DomainStaticFactoryValidator.validate(
                project,
                new HexagonalConventionExtension()
        )

        // then
        assert violations.isEmpty()
    }

    @Test
    @DisplayName('줄바꿈된 생성자와 factory modifier를 AST로 인식한다')
    void acceptsMultilineConstructorAndFactoryModifiers() {
        // given
        Project project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        writeJava(project, 'com/example/order/domain/model/Order.java', '''
                package com.example.order.domain.model;

                public final class Order {

                    private
                    Order() {
                    }

                    public
                    static
                    Order create() {
                        return new Order();
                    }
                }
                ''')

        // when
        List<String> violations = DomainStaticFactoryValidator.validate(
                project,
                new HexagonalConventionExtension()
        )

        // then
        assert violations.isEmpty()
    }

    @Test
    @DisplayName('주석의 record 단어로 class factory 검증을 건너뛰지 않는다')
    void doesNotTreatRecordWordInCommentAsTypeKind() {
        // given
        Project project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        writeJava(project, 'com/example/order/domain/model/Order.java', '''
                package com.example.order.domain.model;

                // record migration is planned later
                public final class Order {
                    public Order() {
                    }
                }
                ''')

        // when
        List<String> violations = DomainStaticFactoryValidator.validate(
                project,
                new HexagonalConventionExtension()
        )

        // then
        assert violations.any { it.contains('must not expose public/protected constructor') }
        assert violations.any { it.contains('must declare a private constructor') }
        assert violations.any { it.contains("must declare at least one public static factory method returning 'Order'") }
    }

    @Test
    @DisplayName('fully-qualified Lombok private constructor annotation을 AST로 인식한다')
    void acceptsFullyQualifiedLombokPrivateConstructor() {
        // given
        Project project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        writeJava(project, 'com/example/order/domain/model/Order.java', '''
                package com.example.order.domain.model;

                @lombok.NoArgsConstructor(
                    access = lombok.AccessLevel.PRIVATE
                )
                public final class Order {
                    public static Order create() {
                        return new Order();
                    }
                }
                ''')

        // when
        List<String> violations = DomainStaticFactoryValidator.validate(
                project,
                new HexagonalConventionExtension()
        )

        // then
        assert violations.isEmpty()
    }

    private static void writeJava(Project project, String path, String source) {
        File file = new File(project.projectDir, "src/main/java/${path}")
        file.parentFile.mkdirs()
        file.text = source.stripIndent().trim() + System.lineSeparator()
    }
}
