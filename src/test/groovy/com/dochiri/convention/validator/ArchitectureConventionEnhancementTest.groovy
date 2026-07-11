package com.dochiri.convention.validator

import com.dochiri.convention.extension.HexagonalConventionExtension
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ArchitectureConventionEnhancementTest {

    @TempDir
    File tempDir

    @Test
    @DisplayName('Application에서 허용하지 않은 Spring 기술 타입 의존을 거부한다')
    void rejectsTechnicalFrameworkDependencyInApplication() {
        // given
        Project project = sampleProject('technical-dependency')
        writeApplication(project)
        writeJava(project, 'com/example/order/application/port/out/OrderQueryPort.java', '''
                package com.example.order.application.port.out;

                import org.springframework.data.domain.Page;

                public interface OrderQueryPort {
                    Page<String> findAll();
                }
                ''')

        // when
        List<String> violations = JavaSourceArchitectureValidator.validate(
                project,
                new HexagonalConventionExtension()
        )

        // then
        assert violations.any {
            it.contains("application must not depend on technical framework type 'org.springframework.data.domain.Page'")
        }
    }

    @Test
    @DisplayName('fully-qualified Spring 기술 타입도 Application에서 거부한다')
    void rejectsFullyQualifiedTechnicalFrameworkDependencyInApplication() {
        // given
        Project project = sampleProject('fully-qualified-technical-dependency')
        writeApplication(project)
        writeJava(project, 'com/example/order/application/port/out/OrderQueryPort.java', '''
                package com.example.order.application.port.out;

                public interface OrderQueryPort {
                    org.springframework.data.domain.Page<String> findAll();
                }
                ''')

        // when
        List<String> violations = JavaSourceArchitectureValidator.validate(
                project,
                new HexagonalConventionExtension()
        )

        // then
        assert violations.any {
            it.contains("application must not depend on technical framework type 'org.springframework.data.domain.Page'")
        }
    }

    @Test
    @DisplayName('Application은 다른 Context Domain 객체 대신 식별자 VO만 참조한다')
    void rejectsCrossContextDomainObjectAndAcceptsIdentifierValueObject() {
        // given
        Project project = sampleProject('cross-context-application')
        writeApplication(project)
        writeJava(project, 'com/example/sales/domain/model/Order.java', '''
                package com.example.sales.domain.model;

                public record Order() {
                }
                ''')
        writeJava(project, 'com/example/sales/domain/model/OrderId.java', '''
                package com.example.sales.domain.model;

                public record OrderId(String value) {

                    public OrderId {
                        if (value == null) {
                            throw new IllegalArgumentException();
                        }
                        if (value.isBlank()) {
                            throw new IllegalArgumentException();
                        }
                    }

                    public static OrderId generate() {
                        return new OrderId("order-id");
                    }
                }
                ''')
        writeJava(project, 'com/example/review/application/port/out/ReviewOrderPort.java', '''
                package com.example.review.application.port.out;

                import com.example.sales.domain.model.Order;
                import com.example.sales.domain.model.OrderId;

                public interface ReviewOrderPort {
                    Order find(OrderId orderId);
                }
                ''')

        // when
        List<String> violations = JavaSourceArchitectureValidator.validate(
                project,
                new HexagonalConventionExtension()
        )

        // then
        assert violations.any {
            it.contains("application must not depend on another context domain model 'com.example.sales.domain.model.Order'")
        }
        assert !violations.any {
            it.contains("another context domain model 'com.example.sales.domain.model.OrderId'")
        }
    }

    @Test
    @DisplayName('fully-qualified 다른 Context Domain 객체 참조도 거부한다')
    void rejectsFullyQualifiedCrossContextDomainObject() {
        // given
        Project project = sampleProject('fully-qualified-cross-context')
        writeApplication(project)
        writeJava(project, 'com/example/review/application/port/out/ReviewOrderPort.java', '''
                package com.example.review.application.port.out;

                public interface ReviewOrderPort {
                    com.example.sales.domain.model.Order find();
                }
                ''')

        // when
        List<String> violations = JavaSourceArchitectureValidator.validate(
                project,
                new HexagonalConventionExtension()
        )

        // then
        assert violations.any {
            it.contains("application must not depend on another context domain model 'com.example.sales.domain.model.Order'")
        }
    }

    @Test
    @DisplayName('Context 이름과 다른 Aggregate 타입도 직접 참조를 거부한다')
    void rejectsCrossContextAggregateWhoseNameDiffersFromContext() {
        // given
        Project project = sampleProject('cross-context-domain')
        writeApplication(project)
        writeJava(project, 'com/example/sales/domain/model/Order.java', '''
                package com.example.sales.domain.model;

                public record Order() {
                }
                ''')
        writeJava(project, 'com/example/review/domain/model/Review.java', '''
                package com.example.review.domain.model;

                import com.example.sales.domain.model.Order;

                public record Review(Order order) {

                    public Review {
                        if (order == null) {
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
            it.contains("domain must not depend directly on another context model 'Order'")
        }
        assert !violations.any {
            it.contains("must reference other aggregate 'Order' by identifier VO")
        }
    }

    @Test
    @DisplayName('다른 Context 값 타입 위반은 Aggregate로 단정하지 않고 번역 방법을 안내한다')
    void reportsCrossContextValueTypeWithoutMisclassifyingItAsAggregate() {
        // given
        Project project = sampleProject('cross-context-value-object')
        writeApplication(project)
        writeJava(project, 'com/example/catalog/domain/model/Money.java', '''
                package com.example.catalog.domain.model;

                public record Money(int amount) {
                }
                ''')
        writeJava(project, 'com/example/order/domain/model/OrderTotal.java', '''
                package com.example.order.domain.model;

                import com.example.catalog.domain.model.Money;

                public record OrderTotal(Money value) {

                    public OrderTotal {
                        if (value == null) {
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
            it.contains("domain must not depend directly on another context model 'Money'")
                    && it.contains('translate it into a context-owned type')
        }
        assert !violations.any {
            it.contains("must reference other aggregate 'Money' by identifier VO")
        }
    }

    @Test
    @DisplayName('사용자 노출 메시지는 한국어로 검증하고 내부 기술 메시지는 제한하지 않는다')
    void validatesOnlyUserFacingMessageLanguage() {
        // given
        Project project = sampleProject('message-language')
        writeApplication(project)
        writeJava(project, 'com/example/member/adapter/out/persistence/PersistenceGuard.java', '''
                package com.example.member.adapter.out.persistence;

                import org.springframework.stereotype.Component;

                @Component
                public final class PersistenceGuard {

                    void fail() {
                        throw new IllegalStateException("internal persistence failure");
                    }
                }
                ''')
        writeJava(project, 'com/example/member/adapter/in/web/MemberErrorMessageProvider.java', '''
                package com.example.member.adapter.in.web;

                import com.example.global.error.ApiErrorMessage;
                import com.example.global.error.ApiErrorMessageProvider;
                import java.util.Map;

                public final class MemberErrorMessageProvider implements ApiErrorMessageProvider {

                    public Map<String, ApiErrorMessage> errorMessages() {
                        return Map.ofEntries(message("Invalid member", "Member does not exist"));
                    }

                    private static Map.Entry<String, ApiErrorMessage> message(
                            final String title,
                            final String detail) {
                        return Map.entry("MEMBER.NOT_FOUND", new ApiErrorMessage(title, detail));
                    }
                }
                ''')

        // when
        List<String> violations = JavaSourceArchitectureValidator.validate(
                project,
                new HexagonalConventionExtension()
        )

        // then
        assert !violations.any { it.contains('exception message string literals must be written in Korean') }
        assert violations.any { it.contains('user-facing ApiErrorMessage title/detail must be written in Korean') }
    }

    @Test
    @DisplayName('Mapper는 변환 방향 이름을 사용하고 비결정 값을 생성하지 않는다')
    void rejectsMapperWithAmbiguousMethodAndUuidGeneration() {
        // given
        Project project = sampleProject('mapper-responsibility')
        writeApplication(project)
        writeJava(project, 'com/example/order/adapter/out/persistence/OrderMapper.java', '''
                package com.example.order.adapter.out.persistence;

                import java.util.UUID;

                public final class OrderMapper {

                    private OrderMapper() {
                    }

                    static String map(final String value) {
                        return value + UUID.randomUUID();
                    }
                }
                ''')

        // when
        List<String> violations = JavaSourceArchitectureValidator.validate(
                project,
                new HexagonalConventionExtension()
        )

        // then
        assert violations.any { it.contains("mapper 'OrderMapper' method 'map' must reveal conversion direction") }
        assert violations.any { it.contains("mapper 'OrderMapper' must not generate time or UUID values") }
    }

    @Test
    @DisplayName('Mapper의 복수 Entity 변환 메서드는 방향이 드러나면 허용한다')
    void acceptsMapperMethodThatTargetsPluralEntities() {
        // given
        Project project = sampleProject('mapper-plural-entities')
        writeApplication(project)
        writeJava(project, 'com/example/order/adapter/out/persistence/OrderMapper.java', '''
                package com.example.order.adapter.out.persistence;

                import java.util.List;

                public final class OrderMapper {

                    private OrderMapper() {
                    }

                    static List<String> toItemEntities(final List<String> values) {
                        return List.copyOf(values);
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
            it.contains("mapper 'OrderMapper' method 'toItemEntities' must reveal conversion direction")
        }
    }

    @Test
    @DisplayName('Application에서 임의 외부 라이브러리 타입 의존을 거부한다')
    void rejectsUnapprovedExternalLibraryDependencyInApplication() {
        // given
        Project project = sampleProject('external-library-dependency')
        writeApplication(project)
        writeJava(project, 'com/example/order/application/port/out/PaymentPort.java', '''
                package com.example.order.application.port.out;

                import com.vendor.payment.PaymentClient;

                public interface PaymentPort {
                    PaymentClient client();
                }
                ''')

        // when
        List<String> violations = JavaSourceArchitectureValidator.validate(
                project,
                new HexagonalConventionExtension()
        )

        // then
        assert violations.any {
            it.contains("application dependency 'com.vendor.payment.PaymentClient' is not allowed")
        }
    }

    @Test
    @DisplayName('Application Service가 같은 Context의 다른 Service 구현체에 의존하는 것을 거부한다')
    void rejectsSameContextApplicationServiceImplementationDependency() {
        // given
        Project project = sampleProject('same-context-service-dependency')
        writeApplication(project)
        writeJava(project, 'com/example/order/application/service/CreateOrderService.java', '''
                package com.example.order.application.service;

                public final class CreateOrderService {
                }
                ''')
        writeJava(project, 'com/example/order/application/service/PayOrderService.java', '''
                package com.example.order.application.service;

                import com.example.order.application.service.CreateOrderService;

                public final class PayOrderService {
                    private final CreateOrderService createOrderService;
                }
                ''')

        // when
        List<String> violations = JavaSourceArchitectureValidator.validate(
                project,
                new HexagonalConventionExtension()
        )

        // then
        assert violations.any {
            it.contains("application dependency 'com.example.order.application.service.CreateOrderService' is not allowed")
        }
    }

    @Test
    @DisplayName('fully-qualified Application Service 구현체 의존도 허용 목록을 우회할 수 없다')
    void rejectsFullyQualifiedSameContextServiceDependency() {
        // given
        Project project = sampleProject('fully-qualified-service-dependency')
        writeApplication(project)
        writeJava(project, 'com/example/order/application/service/PayOrderService.java', '''
                package com.example.order.application.service;

                public final class PayOrderService {
                    private com.example.order.application.service.CreateOrderService createOrderService;
                }
                ''')

        // when
        List<String> violations = JavaSourceArchitectureValidator.validate(
                project,
                new HexagonalConventionExtension()
        )

        // then
        assert violations.any {
            it.contains("application dependency 'com.example.order.application.service.CreateOrderService' is not allowed")
        }
    }

    @Test
    @DisplayName('Application은 JDK와 같은 Context Domain Port 예외 및 허용 annotation을 사용한다')
    void acceptsExplicitApplicationDependencyAllowlist() {
        // given
        Project project = sampleProject('application-allowlist')
        writeApplication(project)
        writeJava(project, 'com/example/order/domain/model/OrderId.java', '''
                package com.example.order.domain.model;

                public record OrderId(String value) {
                }
                ''')
        writeJava(project, 'com/example/order/application/exception/OrderNotFoundException.java', '''
                package com.example.order.application.exception;

                public final class OrderNotFoundException extends RuntimeException {
                }
                ''')
        writeJava(project, 'com/example/order/application/port/out/OrderRepositoryPort.java', '''
                package com.example.order.application.port.out;

                import com.example.order.domain.model.OrderId;
                import java.util.Optional;

                public interface OrderRepositoryPort {
                    Optional<OrderId> find(OrderId orderId);
                }
                ''')
        writeJava(project, 'com/example/order/application/port/in/FindOrderUseCase.java', '''
                package com.example.order.application.port.in;

                public interface FindOrderUseCase {
                    void find();
                }
                ''')
        writeJava(project, 'com/example/order/application/service/FindOrderService.java', '''
                package com.example.order.application.service;

                import com.example.order.application.exception.OrderNotFoundException;
                import com.example.order.application.port.in.FindOrderUseCase;
                import com.example.order.application.port.out.OrderRepositoryPort;
                import lombok.RequiredArgsConstructor;
                import org.springframework.stereotype.Service;
                import org.springframework.transaction.annotation.Transactional;

                @Service
                @RequiredArgsConstructor
                public final class FindOrderService implements FindOrderUseCase {
                    private final OrderRepositoryPort orderRepositoryPort;

                    @Override
                    @Transactional(readOnly = true)
                    public void find() {
                        OrderNotFoundException.class.getName();
                    }
                }
                ''')

        // when
        List<String> violations = JavaSourceArchitectureValidator.validate(
                project,
                new HexagonalConventionExtension()
        )

        // then
        assert !violations.any { it.contains('application dependency') && it.contains('is not allowed') }
    }

    @Test
    @DisplayName('Controller collaborator는 Domain 객체가 아니라 Inbound UseCase만 허용한다')
    void rejectsControllerDomainObjectCollaborator() {
        // given
        Project project = sampleProject('controller-collaborator')
        writeApplication(project)
        writeJava(project, 'com/example/order/domain/model/Order.java', '''
                package com.example.order.domain.model;

                public record Order() {
                }
                ''')
        writeJava(project, 'com/example/order/application/port/in/GetOrderUseCase.java', '''
                package com.example.order.application.port.in;

                public interface GetOrderUseCase {
                    void get();
                }
                ''')
        writeJava(project, 'com/example/order/adapter/in/web/OrderController.java', '''
                package com.example.order.adapter.in.web;

                import com.example.order.application.port.in.GetOrderUseCase;
                import com.example.order.domain.model.Order;

                public final class OrderController {
                    private final GetOrderUseCase getOrderUseCase;
                    private final Order order;
                }
                ''')

        // when
        List<String> violations = JavaSourceArchitectureValidator.validate(
                project,
                new HexagonalConventionExtension()
        )

        // then
        assert violations.any {
            it.contains("controller collaborator 'order' must be an inbound UseCase")
        }
        assert !violations.any {
            it.contains("controller collaborator 'getOrderUseCase' must be an inbound UseCase")
        }
    }

    @Test
    @DisplayName('Application Service collaborator는 Clock 대신 Outbound Port로 추상화한다')
    void rejectsApplicationServiceTechnicalCollaboratorThatIsNotPort() {
        // given
        Project project = sampleProject('service-collaborator')
        writeApplication(project)
        writeJava(project, 'com/example/order/application/port/in/CreateOrderUseCase.java', '''
                package com.example.order.application.port.in;

                public interface CreateOrderUseCase {
                    void create();
                }
                ''')
        writeJava(project, 'com/example/order/application/port/out/OrderRepositoryPort.java', '''
                package com.example.order.application.port.out;

                public interface OrderRepositoryPort {
                    void save();
                }
                ''')
        writeJava(project, 'com/example/order/application/service/CreateOrderService.java', '''
                package com.example.order.application.service;

                import com.example.order.application.port.in.CreateOrderUseCase;
                import com.example.order.application.port.out.OrderRepositoryPort;
                import java.time.Clock;
                import lombok.RequiredArgsConstructor;
                import org.springframework.stereotype.Service;
                import org.springframework.transaction.annotation.Transactional;

                @Service
                @RequiredArgsConstructor
                public final class CreateOrderService implements CreateOrderUseCase {
                    private final OrderRepositoryPort orderRepositoryPort;
                    private final Clock clock;

                    @Override
                    @Transactional
                    public void create() {
                        orderRepositoryPort.save();
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
            it.contains("application service collaborator 'clock' must be an outbound Port or Domain service")
        }
        assert !violations.any {
            it.contains("application service collaborator 'orderRepositoryPort' must be")
        }
    }

    @Test
    @DisplayName('줄바꿈된 final class 선언도 Application Service의 final로 인식한다')
    void recognizesMultilineFinalApplicationServiceDeclaration() {
        // given
        Project project = sampleProject('multiline-final-service')
        writeApplication(project)
        writeJava(project, 'com/example/order/application/port/in/RunOrderUseCase.java', '''
                package com.example.order.application.port.in;

                public interface RunOrderUseCase {
                    void run();
                }
                ''')
        writeJava(project, 'com/example/order/application/service/RunOrderService.java', '''
                package com.example.order.application.service;

                import com.example.order.application.port.in.RunOrderUseCase;
                import org.springframework.stereotype.Service;
                import org.springframework.transaction.annotation.Transactional;

                @Service
                public
                final
                class RunOrderService implements RunOrderUseCase {
                    @Override
                    @Transactional
                    public void run() {
                    }
                }
                ''')

        // when
        List<String> violations = JavaSourceArchitectureValidator.validate(
                project,
                new HexagonalConventionExtension()
        )

        // then
        assert !violations.any { it.contains("application service 'RunOrderService' must be final") }
    }

    @Test
    @DisplayName('줄바꿈된 외부 타입 import도 Application 허용 목록을 우회하지 못한다')
    void rejectsMultilineExternalImportInApplication() {
        // given
        Project project = sampleProject('multiline-external-import')
        writeApplication(project)
        writeJava(project, 'com/example/order/application/port/out/PaymentPort.java', '''
                package com.example.order.application.port.out;

                import com.vendor.payment
                    .PaymentClient;

                public interface PaymentPort {
                    PaymentClient client();
                }
                ''')

        // when
        List<String> violations = JavaSourceArchitectureValidator.validate(
                project,
                new HexagonalConventionExtension()
        )

        // then
        assert violations.any {
            it.contains("application dependency 'com.vendor.payment.PaymentClient' is not allowed")
        }
    }

    @Test
    @DisplayName('쉼표 인자를 가진 annotation 뒤의 record component도 AST로 검증한다')
    void validatesAnnotatedRecordComponentsWithNestedCommas() {
        // given
        Project project = sampleProject('annotated-record-components')
        writeApplication(project)
        writeJava(project, 'com/example/order/domain/model/Orders.java', '''
                package com.example.order.domain.model;

                import java.util.List;

                public record Orders(
                        @Example(min = 1, max = 5) List<String> values,
                        OrderId id
                ) {
                    public Orders {
                        if (values == null || id == null) {
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
            it.contains("domain record component 'values' must be wrapped in a first-class collection")
        }
    }

    @Test
    @DisplayName('type-use annotation이 붙은 Domain field도 raw scalar와 식별자 규칙을 검증한다')
    void validatesTypeUseAnnotatedDomainField() {
        // given
        Project project = sampleProject('type-use-field')
        writeApplication(project)
        writeJava(project, 'com/example/order/domain/model/ReferenceHolder.java', '''
                package com.example.order.domain.model;

                public final class ReferenceHolder {
                    private @NonNull String memberId;

                    private ReferenceHolder() {
                    }

                    public static ReferenceHolder create() {
                        return new ReferenceHolder();
                    }
                }
                ''')

        // when
        List<String> violations = JavaSourceArchitectureValidator.validate(
                project,
                new HexagonalConventionExtension()
        )

        // then
        assert violations.any { it.contains("domain field 'memberId' must use a Value Object") }
        assert violations.any {
            it.contains("domain reference field 'memberId' must use an identifier Value Object type")
        }
    }

    @Test
    @DisplayName('record component를 일반 필드로 중복 판정하지 않는다')
    void doesNotTreatRecordComponentsAsDeclaredFields() {
        // given
        Project project = sampleProject('record-component-separation')
        writeApplication(project)
        writeJava(project, 'com/example/order/domain/model/Email.java', '''
                package com.example.order.domain.model;

                public record Email(String value) {
                    public Email {
                        if (value == null) {
                            throw new IllegalArgumentException();
                        }
                        if (value.isBlank()) {
                            throw new IllegalArgumentException();
                        }
                    }
                }
                ''')
        writeJava(project, 'com/example/order/domain/model/OrderLines.java', '''
                package com.example.order.domain.model;

                import java.util.List;

                public record OrderLines(List<OrderLine> values) {
                    public OrderLines {
                        if (values == null || values.contains(null)) {
                            throw new IllegalArgumentException();
                        }
                        values = List.copyOf(values);
                    }
                }
                ''')
        writeJava(project, 'com/example/order/domain/model/OrderLine.java', '''
                package com.example.order.domain.model;

                public record OrderLine(OrderLineId id) {
                    public OrderLine {
                        if (id == null) {
                            throw new IllegalArgumentException();
                        }
                    }
                    public static OrderLine create(final OrderLineId id) {
                        return new OrderLine(id);
                    }
                    @Override
                    public boolean equals(final Object other) {
                        return this == other || other instanceof OrderLine target && id.equals(target.id);
                    }
                    @Override
                    public int hashCode() {
                        return id.hashCode();
                    }
                }
                ''')
        writeJava(project, 'com/example/order/domain/model/OrderLineId.java', '''
                package com.example.order.domain.model;

                public record OrderLineId(String value) {
                    public OrderLineId {
                        if (value == null) {
                            throw new IllegalArgumentException();
                        }
                        if (value.isBlank()) {
                            throw new IllegalArgumentException();
                        }
                    }
                    public static OrderLineId generate() {
                        return new OrderLineId("id");
                    }
                }
                ''')

        // when
        List<String> violations = JavaSourceArchitectureValidator.validate(
                project,
                new HexagonalConventionExtension()
        )

        // then
        assert !violations.any { it.contains("domain field 'value'") }
        assert !violations.any { it.contains("domain field 'values'") }
        assert !violations.any { it.contains("domain record component 'value'") }
        assert !violations.any { it.contains("domain record component 'values'") }
    }

    @Test
    @DisplayName('fully-qualified JPA field 선언을 AST에서 동등하게 인식한다')
    void acceptsFullyQualifiedJpaFieldDeclarations() {
        // given
        Project project = sampleProject('qualified-jpa-fields')
        writeApplication(project)
        writeJava(project, 'com/example/order/adapter/out/persistence/OrderEntity.java', '''
                package com.example.order.adapter.out.persistence;

                @jakarta.persistence.Entity
                @jakarta.persistence.Table(name = "orders")
                @lombok.Getter
                @lombok.NoArgsConstructor(
                    access = lombok.AccessLevel.PROTECTED
                )
                public class OrderEntity {

                    @jakarta.persistence.Id
                    @lombok.Getter(lombok.AccessLevel.NONE)
                    private java.lang.Long id;

                    @jakarta.persistence.Column(
                        length = 32,
                        unique = true
                    )
                    @java.lang.Deprecated
                    private java.lang.String orderId;

                    private OrderEntity(final java.lang.String orderId) {
                        this.orderId = orderId;
                    }

                    static OrderEntity from(final java.lang.String orderId) {
                        return new OrderEntity(orderId);
                    }
                }
                ''')

        // when
        List<String> violations = JavaSourceArchitectureValidator.validate(
                project,
                new HexagonalConventionExtension()
        )

        // then
        assert !violations.any { it.contains("JPA entity 'OrderEntity' must declare @NoArgsConstructor") }
        assert !violations.any { it.contains("JPA entity 'OrderEntity' must declare private Long id with @Id") }
        assert !violations.any { it.contains("JPA technical key 'id' must declare @Getter(AccessLevel.NONE)") }
        assert !violations.any { it.contains("must declare private String orderId with @Column") }
        assert !violations.any { it.contains("domain identifier column 'orderId' must be unique") }
        assert !violations.any { it.contains("domain identifier column 'orderId' must have length = 32") }
        assert !violations.any { it.contains("argument constructors must be private") }
        assert !violations.any { it.contains("must expose a static factory returning 'OrderEntity'") }
    }

    @Test
    @DisplayName('multiline relation annotation과 generic Mapper method를 AST로 검출한다')
    void detectsMultilineJpaRelationAndGenericMapperMethod() {
        // given
        Project project = sampleProject('jpa-relation-and-generic-mapper')
        writeApplication(project)
        writeJava(project, 'com/example/order/adapter/out/persistence/OrderEntity.java', '''
                package com.example.order.adapter.out.persistence;

                @jakarta.persistence.Entity
                @lombok.Getter
                @lombok.NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
                public class OrderEntity {
                    @jakarta.persistence.Id
                    @lombok.Getter(lombok.AccessLevel.NONE)
                    private java.lang.Long id;

                    @jakarta.persistence.Column(unique = true, length = 32)
                    private java.lang.String orderId;

                    private OrderEntity(final java.lang.String orderId) {
                        this.orderId = orderId;
                    }

                    static OrderEntity from(final java.lang.String orderId) {
                        return new OrderEntity(orderId);
                    }

                    @jakarta.persistence
                        .ManyToOne
                    private ProductEntity product() {
                        return null;
                    }
                }
                ''')
        writeJava(project, 'com/example/order/adapter/out/persistence/OrderMapper.java', '''
                package com.example.order.adapter.out.persistence;

                public final class OrderMapper {
                    private OrderMapper() {
                    }

                    static <T> T map(final T value) {
                        return value;
                    }
                }
                ''')

        // when
        List<String> violations = JavaSourceArchitectureValidator.validate(
                project,
                new HexagonalConventionExtension()
        )

        // then
        assert violations.any { it.contains("JPA entity 'OrderEntity' must not use object relation mappings") }
        assert violations.any {
            it.contains("mapper 'OrderMapper' method 'map' must reveal conversion direction")
        }
    }

    @Test
    @DisplayName('fully-qualified superclass와 outbound Port 구현을 AST로 인식한다')
    void acceptsFullyQualifiedSuperclassAndOutboundPortImplementation() {
        // given
        Project project = sampleProject('qualified-super-and-port')
        writeApplication(project)
        writeJava(project, 'com/example/order/domain/exception/InvalidOrderException.java', '''
                package com.example.order.domain.exception;

                public final class InvalidOrderException extends java.lang.RuntimeException {
                    private static final long serialVersionUID = 1L;

                    private InvalidOrderException() {
                    }

                    public static InvalidOrderException invalid() {
                        return new InvalidOrderException();
                    }
                }
                ''')
        writeJava(project, 'com/example/order/application/port/out/OrderPort.java', '''
                package com.example.order.application.port.out;

                public interface OrderPort {
                    void execute();
                }
                ''')
        writeJava(project, 'com/example/order/adapter/out/persistence/OrderPersistenceAdapter.java', '''
                package com.example.order.adapter.out.persistence;

                @org.springframework.stereotype.Repository
                public final class OrderPersistenceAdapter implements
                        com.example.order.application.port.out.OrderPort {
                    @Override
                    public void execute() {
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
            it.contains("domain exception 'InvalidOrderException' must extend RuntimeException")
        }
        assert !violations.any {
            it.contains("persistence adapter 'OrderPersistenceAdapter' must implement an outbound Port")
        }
    }

    @Test
    @DisplayName('type-use annotation이 붙은 Controller 반환 타입도 AST로 검증한다')
    void detectsAnnotatedControllerDomainReturnType() {
        // given
        Project project = sampleProject('annotated-controller-return')
        writeApplication(project)
        writeJava(project, 'com/example/order/domain/model/Order.java', '''
                package com.example.order.domain.model;

                public final class Order {
                    private Order() {
                    }

                    public static Order create() {
                        return new Order();
                    }
                }
                ''')
        writeJava(project, 'com/example/order/adapter/in/web/OrderController.java', '''
                package com.example.order.adapter.in.web;

                import com.example.order.domain.model.Order;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public final class OrderController {
                    public @Example Order getOrder() {
                        return null;
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
            it.contains('controller must not expose domain types as response return values')
        }
    }

    @Test
    @DisplayName('주석 속 가짜 선언을 record compact constructor로 인정하지 않는다')
    void ignoresCompactConstructorShapeInsideComment() {
        // given
        Project project = sampleProject('compact-constructor-comment')
        writeApplication(project)
        writeJava(project, 'com/example/order/domain/model/OrderName.java', '''
                package com.example.order.domain.model;

                public record OrderName(String value) {
                    /*
                    public OrderName {
                    }
                    */
                }
                ''')

        // when
        List<String> violations = JavaSourceArchitectureValidator.validate(
                project,
                new HexagonalConventionExtension()
        )

        // then
        assert violations.any {
            it.contains("domain record 'OrderName' must declare a compact constructor for invariants")
        }
    }

    @Test
    @DisplayName('줄바꿈된 fully-qualified 기술 타입과 다른 Context 타입을 AST로 거부한다')
    void rejectsMultilineFullyQualifiedApplicationTypes() {
        // given
        Project project = sampleProject('multiline-qualified-types')
        writeApplication(project)
        writeJava(project, 'com/example/order/application/port/out/PaymentPort.java', '''
                package com.example.order.application.port.out;

                public interface PaymentPort {
                    org.springframework
                        .data.domain.Page<String> findAll();

                    com.example.catalog
                        .domain.model.Product findProduct();
                }
                ''')

        // when
        List<String> violations = JavaSourceArchitectureValidator.validate(
                project,
                new HexagonalConventionExtension()
        )

        // then
        assert violations.any {
            it.contains("technical framework type 'org.springframework.data.domain.Page'")
        }
        assert violations.any {
            it.contains("another context domain model 'com.example.catalog.domain.model.Product'")
        }
    }

    @Test
    @DisplayName('줄바꿈된 fully-qualified 다른 Context Domain 참조를 AST로 거부한다')
    void rejectsMultilineFullyQualifiedDomainReference() {
        // given
        Project project = sampleProject('multiline-qualified-domain-reference')
        writeApplication(project)
        writeJava(project, 'com/example/order/domain/model/CatalogSnapshot.java', '''
                package com.example.order.domain.model;

                public record CatalogSnapshot(
                        com.example.catalog
                            .domain.model.Product product
                ) {
                    public CatalogSnapshot {
                        if (product == null) {
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
            it.contains("domain must not depend directly on another context model 'Product'")
        }
    }

    @Test
    @DisplayName('줄바꿈된 fully-qualified 내부 계층 참조를 DTO와 Domain에서 거부한다')
    void rejectsMultilineFullyQualifiedLayerReferences() {
        // given
        Project project = sampleProject('multiline-qualified-layer-references')
        writeApplication(project)
        writeJava(project, 'com/example/order/adapter/in/web/response/GetOrderResponse.java', '''
                package com.example.order.adapter.in.web.response;

                public record GetOrderResponse(
                        com.example.order
                            .domain.model.Order order
                ) {
                }
                ''')
        writeJava(project, 'com/example/order/domain/model/FailureDescriptor.java', '''
                package com.example.order.domain.model;

                public final class FailureDescriptor {
                    private final com.example.global
                        .error.ApiErrorCode errorCode;

                    private FailureDescriptor(final com.example.global
                            .error.ApiErrorCode errorCode) {
                        this.errorCode = errorCode;
                    }

                    public static FailureDescriptor from(final com.example.global
                            .error.ApiErrorCode errorCode) {
                        return new FailureDescriptor(errorCode);
                    }
                }
                ''')

        // when
        List<String> violations = JavaSourceArchitectureValidator.validate(
                project,
                new HexagonalConventionExtension()
        )

        // then
        assert violations.any { it.contains("API DTO 'GetOrderResponse' must not expose domain/application types") }
        assert violations.any { it.contains('domain must not depend on global.error') }
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

    private static void writeJava(Project project, String path, String source) {
        File file = new File(project.projectDir, "src/main/java/${path}")
        file.parentFile.mkdirs()
        file.text = source.stripIndent().trim() + System.lineSeparator()
    }
}
