package com.dochiri.convention.validator

import com.dochiri.convention.extension.HexagonalConventionExtension
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ClaudeConventionValidatorTest {

    @TempDir
    File tempDir

    @Test
    void 'rejects ContextConfig and missing Spring components'() {
        Project project = sampleProject()
        writeJava(project, 'com/example/order/OrderContextConfig.java', '''
                package com.example.order;

                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;

                @Configuration
                public class OrderContextConfig {

                    @Bean
                    String orderBean() {
                        return "order";
                    }
                }
                ''')
        writeJava(project, 'com/example/order/application/port/in/RegisterOrderUseCase.java', '''
                package com.example.order.application.port.in;

                public interface RegisterOrderUseCase {
                }
                ''')
        writeJava(project, 'com/example/order/application/service/RegisterOrderService.java', '''
                package com.example.order.application.service;

                import com.example.order.application.port.in.RegisterOrderUseCase;

                public final class RegisterOrderService implements RegisterOrderUseCase {
                }
                ''')
        writeJava(project, 'com/example/order/application/port/out/PaymentPort.java', '''
                package com.example.order.application.port.out;

                public interface PaymentPort {
                }
                ''')
        writeJava(project, 'com/example/order/adapter/out/payment/PaymentAdapter.java', '''
                package com.example.order.adapter.out.payment;

                import com.example.order.application.port.out.PaymentPort;

                public final class PaymentAdapter implements PaymentPort {
                }
                ''')

        List<String> violations = ClaudeConventionValidator.validate(project, new HexagonalConventionExtension())

        assert violations.any { it.contains('ContextConfig is not allowed') }
        assert violations.any { it.contains("application service 'RegisterOrderService' must declare @Service") }
        assert violations.any { it.contains("outbound adapter 'PaymentAdapter' must declare @Component or @Repository") }
    }

    @Test
    void 'accepts component registered service and outbound adapter'() {
        Project project = sampleProject()
        writeApplication(project)
        writeJava(project, 'com/example/order/application/port/in/RegisterOrderUseCase.java', '''
                package com.example.order.application.port.in;

                public interface RegisterOrderUseCase {
                }
                ''')
        writeJava(project, 'com/example/order/application/service/RegisterOrderService.java', '''
                package com.example.order.application.service;

                import com.example.order.application.port.in.RegisterOrderUseCase;
                import org.springframework.stereotype.Service;

                @Service
                public final class RegisterOrderService implements RegisterOrderUseCase {
                }
                ''')
        writeJava(project, 'com/example/order/application/port/out/PaymentPort.java', '''
                package com.example.order.application.port.out;

                public interface PaymentPort {
                }
                ''')
        writeJava(project, 'com/example/order/adapter/out/payment/PaymentAdapter.java', '''
                package com.example.order.adapter.out.payment;

                import com.example.order.application.port.out.PaymentPort;
                import org.springframework.stereotype.Component;

                @Component
                public final class PaymentAdapter implements PaymentPort {
                }
                ''')

        List<String> violations = ClaudeConventionValidator.validate(project, new HexagonalConventionExtension())

        assert !violations.any { it.contains('ContextConfig is not allowed') }
        assert !violations.any { it.contains('must declare @Service') }
        assert !violations.any { it.contains('must declare @Component or @Repository') }
    }

    @Test
    void 'accepts method level transaction and required args constructor injection'() {
        Project project = sampleProject()
        writeApplication(project)
        writeJava(project, 'com/example/order/application/port/in/RegisterOrderUseCase.java', '''
                package com.example.order.application.port.in;

                public interface RegisterOrderUseCase {
                    void register();
                }
                ''')
        writeJava(project, 'com/example/order/application/port/out/OrderRepositoryPort.java', '''
                package com.example.order.application.port.out;

                public interface OrderRepositoryPort {
                    void save();
                }
                ''')
        writeJava(project, 'com/example/order/application/service/RegisterOrderService.java', '''
                package com.example.order.application.service;

                import com.example.order.application.port.in.RegisterOrderUseCase;
                import com.example.order.application.port.out.OrderRepositoryPort;
                import lombok.RequiredArgsConstructor;
                import org.springframework.stereotype.Service;
                import org.springframework.transaction.annotation.Transactional;

                @Service
                @RequiredArgsConstructor
                public final class RegisterOrderService implements RegisterOrderUseCase {

                    private final OrderRepositoryPort orderRepositoryPort;

                    @Override
                    @Transactional
                    public void register() {
                        orderRepositoryPort.save();
                    }
                }
                ''')

        List<String> violations = ClaudeConventionValidator.validate(project, new HexagonalConventionExtension())

        assert !violations.any { it.contains('@Transactional must be declared on public application service methods') }
        assert !violations.any { it.contains('must declare @RequiredArgsConstructor') }
        assert !violations.any { it.contains('must implement exactly one UseCase') }
    }

    @Test
    void 'rejects class level transaction and missing method level transaction'() {
        Project project = sampleProject()
        writeApplication(project)
        writeJava(project, 'com/example/order/application/port/in/RegisterOrderUseCase.java', '''
                package com.example.order.application.port.in;

                public interface RegisterOrderUseCase {
                    void register();
                }
                ''')
        writeJava(project, 'com/example/order/application/service/RegisterOrderService.java', '''
                package com.example.order.application.service;

                import com.example.order.application.port.in.RegisterOrderUseCase;
                import org.springframework.stereotype.Service;
                import org.springframework.transaction.annotation.Transactional;

                @Service
                @Transactional
                public final class RegisterOrderService implements RegisterOrderUseCase {

                    @Override
                    public void register() {
                    }
                }
                ''')

        List<String> violations = ClaudeConventionValidator.validate(project, new HexagonalConventionExtension())

        assert violations.any { it.contains('@Transactional must be declared on public application service methods, not on the class') }
        assert violations.any { it.contains("public application service method 'register' must declare @Transactional") }
    }

    @Test
    void 'rejects field injection without required args constructor'() {
        Project project = sampleProject()
        writeApplication(project)
        writeJava(project, 'com/example/order/application/port/in/RegisterOrderUseCase.java', '''
                package com.example.order.application.port.in;

                public interface RegisterOrderUseCase {
                    void register();
                }
                ''')
        writeJava(project, 'com/example/order/application/port/out/OrderRepositoryPort.java', '''
                package com.example.order.application.port.out;

                public interface OrderRepositoryPort {
                    void save();
                }
                ''')
        writeJava(project, 'com/example/order/application/service/RegisterOrderService.java', '''
                package com.example.order.application.service;

                import com.example.order.application.port.in.RegisterOrderUseCase;
                import com.example.order.application.port.out.OrderRepositoryPort;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.stereotype.Service;
                import org.springframework.transaction.annotation.Transactional;

                @Service
                public final class RegisterOrderService implements RegisterOrderUseCase {

                    @Autowired
                    private OrderRepositoryPort orderRepositoryPort;

                    @Override
                    @Transactional
                    public void register() {
                        orderRepositoryPort.save();
                    }
                }
                ''')

        List<String> violations = ClaudeConventionValidator.validate(project, new HexagonalConventionExtension())

        assert violations.any { it.contains('must use final fields with @RequiredArgsConstructor instead of @Autowired injection') }
        assert violations.any { it.contains('dependencies must be private final fields') }
    }

    @Test
    void 'rejects service implementing multiple use cases and adapter implementing multiple ports'() {
        Project project = sampleProject()
        writeApplication(project)
        writeJava(project, 'com/example/order/application/port/in/RegisterOrderUseCase.java', '''
                package com.example.order.application.port.in;

                public interface RegisterOrderUseCase {
                    void register();
                }
                ''')
        writeJava(project, 'com/example/order/application/port/in/CancelOrderUseCase.java', '''
                package com.example.order.application.port.in;

                public interface CancelOrderUseCase {
                    void cancel();
                }
                ''')
        writeJava(project, 'com/example/order/application/service/OrderService.java', '''
                package com.example.order.application.service;

                import com.example.order.application.port.in.CancelOrderUseCase;
                import com.example.order.application.port.in.RegisterOrderUseCase;
                import org.springframework.stereotype.Service;
                import org.springframework.transaction.annotation.Transactional;

                @Service
                public final class OrderService implements RegisterOrderUseCase, CancelOrderUseCase {

                    @Override
                    @Transactional
                    public void register() {
                    }

                    @Override
                    @Transactional
                    public void cancel() {
                    }
                }
                ''')
        writeJava(project, 'com/example/order/application/port/out/PaymentPort.java', '''
                package com.example.order.application.port.out;

                public interface PaymentPort {
                }
                ''')
        writeJava(project, 'com/example/order/application/port/out/RefundPort.java', '''
                package com.example.order.application.port.out;

                public interface RefundPort {
                }
                ''')
        writeJava(project, 'com/example/order/adapter/out/payment/PaymentAdapter.java', '''
                package com.example.order.adapter.out.payment;

                import com.example.order.application.port.out.PaymentPort;
                import com.example.order.application.port.out.RefundPort;
                import org.springframework.stereotype.Component;

                @Component
                public final class PaymentAdapter implements PaymentPort, RefundPort {
                }
                ''')

        List<String> violations = ClaudeConventionValidator.validate(project, new HexagonalConventionExtension())

        assert violations.any { it.contains("application service 'OrderService' must implement exactly one UseCase for SRP") }
        assert violations.any { it.contains("adapter 'PaymentAdapter' must implement only one outbound Port for SRP") }
    }

    @Test
    void 'rejects ambiguous responsibility names'() {
        Project project = sampleProject()
        writeApplication(project)
        writeJava(project, 'com/example/order/domain/model/OrderManager.java', '''
                package com.example.order.domain.model;

                public final class OrderManager {
                }
                ''')

        List<String> violations = ClaudeConventionValidator.validate(project, new HexagonalConventionExtension())

        assert violations.any { it.contains("type 'OrderManager' has an ambiguous responsibility name") }
    }

    @Test
    void 'rejects packages outside bounded context topology'() {
        Project project = sampleProject()
        writeApplication(project)
        writeJava(project, 'com/example/order/model/Order.java', '''
                package com.example.order.model;

                public final class Order {
                }
                ''')
        writeJava(project, 'com/example/order/service/RegisterOrderService.java', '''
                package com.example.order.service;

                public final class RegisterOrderService {
                }
                ''')
        writeJava(project, 'com/example/order/web/OrderController.java', '''
                package com.example.order.web;

                public final class OrderController {
                }
                ''')
        writeJava(project, 'com/example/domain/order/Order.java', '''
                package com.example.domain.order;

                public final class Order {
                }
                ''')
        writeJava(project, 'com/example/application/order/RegisterOrderService.java', '''
                package com.example.application.order;

                public final class RegisterOrderService {
                }
                ''')
        writeJava(project, 'com/example/adapter/order/OrderController.java', '''
                package com.example.adapter.order;

                public final class OrderController {
                }
                ''')
        writeJava(project, 'com/example/infrastructure/adapter/out/OrderPersistenceAdapter.java', '''
                package com.example.infrastructure.adapter.out;

                public final class OrderPersistenceAdapter {
                }
                ''')
        writeJava(project, 'com/example/config/OrderProperties.java', '''
                package com.example.config;

                public record OrderProperties(String name) {
                }
                ''')

        List<String> violations = ClaudeConventionValidator.validate(project, new HexagonalConventionExtension())

        assert violations.any { it.contains('package must follow {context}/domain, {context}/application, or {context}/adapter structure') }
        assert violations.any { it.contains('package must be context-first') }
        assert violations.any { it.contains("package 'infrastructure' is not a bounded context") }
        assert violations.any { it.contains("package 'config' is not a bounded context") }
    }

    @Test
    void 'accepts bounded context domain application adapter topology'() {
        Project project = sampleProject()
        writeApplication(project)
        writeJava(project, 'com/example/order/domain/model/OrderStatus.java', '''
                package com.example.order.domain.model;

                public enum OrderStatus {
                    CREATED
                }
                ''')
        writeJava(project, 'com/example/order/application/port/in/RegisterOrderUseCase.java', '''
                package com.example.order.application.port.in;

                public interface RegisterOrderUseCase {
                }
                ''')
        writeJava(project, 'com/example/order/application/service/RegisterOrderService.java', '''
                package com.example.order.application.service;

                import com.example.order.application.port.in.RegisterOrderUseCase;
                import org.springframework.stereotype.Service;

                @Service
                public final class RegisterOrderService implements RegisterOrderUseCase {
                }
                ''')
        writeJava(project, 'com/example/order/application/port/out/PaymentPort.java', '''
                package com.example.order.application.port.out;

                public interface PaymentPort {
                }
                ''')
        writeJava(project, 'com/example/order/adapter/out/payment/PaymentAdapter.java', '''
                package com.example.order.adapter.out.payment;

                import com.example.order.application.port.out.PaymentPort;
                import org.springframework.stereotype.Component;

                @Component
                public final class PaymentAdapter implements PaymentPort {
                }
                ''')
        writeJava(project, 'com/example/order/adapter/in/web/OrderController.java', '''
                package com.example.order.adapter.in.web;

                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public final class OrderController {
                }
                ''')
        writeJava(project, 'com/example/global/error/GlobalExceptionHandler.java', '''
                package com.example.global.error;

                public final class GlobalExceptionHandler {
                }
                ''')

        List<String> violations = ClaudeConventionValidator.validate(project, new HexagonalConventionExtension())

        assert !violations.any { it.contains('package must follow {context}/domain') }
        assert !violations.any { it.contains('root package may contain only') }
        assert !violations.any { it.contains('global package must be limited') }
    }

    @Test
    void 'rejects else and requires early return'() {
        Project project = sampleProject()
        writeApplication(project)
        writeJava(project, 'com/example/global/error/FlowGuard.java', '''
                package com.example.global.error;

                public final class FlowGuard {

                    int select(boolean active) {
                        if (active) {
                            return 1;
                        } else {
                            return 0;
                        }
                    }
                }
                ''')

        List<String> violations = ClaudeConventionValidator.validate(project, new HexagonalConventionExtension())

        assert violations.any { it.contains('must not use else; use guard clauses and early return') }
    }

    @Test
    void 'accepts early return and ignores else in comments and strings'() {
        Project project = sampleProject()
        writeApplication(project)
        writeJava(project, 'com/example/global/error/FlowGuard.java', '''
                package com.example.global.error;

                public final class FlowGuard {

                    String select(boolean active) {
                        // else in comments must not count
                        String label = "else in text must not count";
                        String block = """
                                else in text block must not count
                                """;
                        if (!active) {
                            return label;
                        }
                        return block;
                    }
                }
                ''')

        List<String> violations = ClaudeConventionValidator.validate(project, new HexagonalConventionExtension())

        assert !violations.any { it.contains('must not use else; use guard clauses and early return') }
    }

    @Test
    void 'accepts domain event with past tense name without Event suffix'() {
        Project project = sampleProject()
        writeApplication(project)
        writeJava(project, 'com/example/order/domain/model/OrderId.java', '''
                package com.example.order.domain.model;

                import java.util.Objects;
                import java.util.UUID;

                public record OrderId(UUID value) {

                    public OrderId {
                        Objects.requireNonNull(value);
                    }
                }
                ''')
        writeJava(project, 'com/example/order/domain/event/OrderPlaced.java', '''
                package com.example.order.domain.event;

                import com.example.order.domain.model.OrderId;
                import java.util.Objects;

                public record OrderPlaced(OrderId orderId) {

                    public OrderPlaced {
                        Objects.requireNonNull(orderId);
                    }
                }
                ''')

        List<String> violations = ClaudeConventionValidator.validate(project, new HexagonalConventionExtension())

        assert !violations.any { it.contains("domain event 'OrderPlaced'") }
        assert !violations.any { it.contains("domain model 'OrderPlaced' must live in domain.model package") }
    }

    @Test
    void 'rejects domain event name with Event suffix'() {
        Project project = sampleProject()
        writeApplication(project)
        writeJava(project, 'com/example/order/domain/model/OrderId.java', '''
                package com.example.order.domain.model;

                import java.util.Objects;
                import java.util.UUID;

                public record OrderId(UUID value) {

                    public OrderId {
                        Objects.requireNonNull(value);
                    }
                }
                ''')
        writeJava(project, 'com/example/order/domain/event/OrderPlacedEvent.java', '''
                package com.example.order.domain.event;

                import com.example.order.domain.model.OrderId;
                import java.util.Objects;

                public record OrderPlacedEvent(OrderId orderId) {

                    public OrderPlacedEvent {
                        Objects.requireNonNull(orderId);
                    }
                }
                ''')

        List<String> violations = ClaudeConventionValidator.validate(project, new HexagonalConventionExtension())

        assert violations.any { it.contains("domain event 'OrderPlacedEvent' must use a past-tense name without Event suffix") }
    }

    @Test
    void 'rejects exception architecture violations'() {
        Project project = sampleProject()
        writeApplication(project)
        writeJava(project, 'com/example/order/application/port/out/OrderClientPort.java', '''
                package com.example.order.application.port.out;

                import java.io.IOException;

                public interface OrderClientPort {
                    void request() throws IOException;
                }
                ''')
        writeJava(project, 'com/example/global/error/GlobalExceptionHandler.java', '''
                package com.example.global.error;

                import com.example.order.domain.exception.InvalidOrderException;

                public final class GlobalExceptionHandler {
                }
                ''')
        writeJava(project, 'com/example/order/adapter/in/web/OrderExceptionMapper.java', '''
                package com.example.order.adapter.in.web;

                import org.springframework.http.ProblemDetail;

                public final class OrderExceptionMapper {

                    ProblemDetail map(RuntimeException exception) {
                        ProblemDetail problemDetail = ProblemDetail.forStatus(400);
                        problemDetail.setDetail(exception.getMessage());
                        problemDetail.setTitle("Invalid order");
                        return problemDetail;
                    }
                }
                ''')

        List<String> violations = ClaudeConventionValidator.validate(project, new HexagonalConventionExtension())

        assert violations.any { it.contains('must not expose DB/HTTP/SDK/Spring technical exception types') }
        assert violations.any { it.contains('GlobalExceptionHandler must delegate') }
        assert violations.any { it.contains('must not expose exception.getMessage()') }
        assert violations.any { it.contains('must resolve user-facing ProblemDetail title/detail') }
    }

    @Test
    void 'rejects Java test methods without Korean display name and given when then'() {
        Project project = sampleProject()
        writeTestJava(project, 'com/example/order/OrderServiceTest.java', '''
                package com.example.order;

                import org.junit.jupiter.api.Disabled;
                import org.junit.jupiter.api.DisplayName;
                import org.junit.jupiter.api.Assumptions;
                import org.junit.jupiter.api.Test;

                class OrderServiceTest {

                    @Test
                    void missingDisplayName() {
                        int actual = 1;
                        assert actual == 1;
                    }

                    @Test
                    @DisplayName("creates order")
                    void englishDisplayName() {
                        // given
                        int actual = 1;

                        // when
                        actual++;

                        // then
                        assert actual == 2;
                    }

                    @Test
                    @DisplayName("주문을 생성한다")
                    void missingGivenWhenThen() {
                        int actual = 1;
                        assert actual == 1;
                    }
                }
                ''')

        List<String> violations = ClaudeConventionValidator.validate(project, new HexagonalConventionExtension())

        assert violations.any { it.contains("test method 'missingDisplayName' must declare @DisplayName in Korean") }
        assert violations.any { it.contains("test method 'englishDisplayName' @DisplayName must be written in Korean") }
        assert violations.any { it.contains("test method 'missingGivenWhenThen' must include '// given'") }
        assert violations.any { it.contains("test method 'missingGivenWhenThen' must include '// when'") }
        assert violations.any { it.contains("test method 'missingGivenWhenThen' must include '// then'") }
    }

    @Test
    void 'accepts Java test methods with Korean display name and given when then'() {
        Project project = sampleProject()
        writeTestJava(project, 'com/example/order/OrderServiceTest.java', '''
                package com.example.order;

                import org.junit.jupiter.api.DisplayName;
                import org.junit.jupiter.api.Test;

                class OrderServiceTest {

                    @Test
                    @DisplayName("주문을 생성한다")
                    void createOrder() {
                        // given
                        int actual = 1;

                        // when
                        actual++;

                        // then
                        assert actual == 2;
                    }
                }
                ''')

        List<String> violations = ClaudeConventionValidator.validate(project, new HexagonalConventionExtension())

        assert !violations.any { it.contains('must declare @DisplayName in Korean') }
        assert !violations.any { it.contains('@DisplayName must be written in Korean') }
        assert !violations.any { it.contains("must include '// given'") }
        assert !violations.any { it.contains("must include '// when'") }
        assert !violations.any { it.contains("must include '// then'") }
    }

    @Test
    void 'rejects placeholder given when then comments without section code'() {
        Project project = sampleProject()
        writeTestJava(project, 'com/example/order/OrderServiceTest.java', '''
                package com.example.order;

                import org.junit.jupiter.api.DisplayName;
                import org.junit.jupiter.api.Test;

                class OrderServiceTest {

                    @Test
                    @DisplayName("주문을 생성한다")
                    void createOrder() {
                        // given

                        // when

                        // then

                        int actual = 1;
                        assert actual == 1;
                    }
                }
                ''')

        List<String> violations = ClaudeConventionValidator.validate(project, new HexagonalConventionExtension())

        assert violations.any { it.contains("// given section must contain code") }
        assert violations.any { it.contains("// when section must contain code") }
    }

    @Test
    void 'accepts when then combined section for exception assertions'() {
        Project project = sampleProject()
        writeTestJava(project, 'com/example/order/OrderServiceTest.java', '''
                package com.example.order;

                import org.junit.jupiter.api.DisplayName;
                import org.junit.jupiter.api.Test;

                import static org.assertj.core.api.Assertions.assertThatThrownBy;

                class OrderServiceTest {

                    @Test
                    @DisplayName("취소된 주문은 다시 취소할 수 없다")
                    void cannotCancelTwice() {
                        // given
                        Runnable command = () -> {
                            throw new IllegalStateException("cancelled");
                        };

                        // when & then
                        assertThatThrownBy(command::run)
                                .isInstanceOf(IllegalStateException.class);
                    }
                }
                ''')

        List<String> violations = ClaudeConventionValidator.validate(project, new HexagonalConventionExtension())

        assert !violations.any { it.contains("must include '// when'") }
        assert !violations.any { it.contains("must include '// then'") }
        assert !violations.any { it.contains('section must contain code') }
    }

    @Test
    void 'rejects weak Java test assertions'() {
        Project project = sampleProject()
        writeTestJava(project, 'com/example/order/OrderServiceTest.java', '''
                package com.example.order;

                import org.junit.jupiter.api.DisplayName;
                import org.junit.jupiter.api.Test;

                class OrderServiceTest {

                    @Test
                    @DisplayName("주문 생성 흐름을 실행한다")
                    void assertionless() {
                        // given
                        int actual = 1;

                        // when
                        actual++;

                        // then
                        System.out.println(actual);
                    }

                    @Test
                    @DisplayName("주문 생성 시 예외가 발생하지 않는다")
                    void noExceptionOnly() {
                        // given
                        Runnable command = () -> {};

                        // when
                        Runnable actual = command;

                        // then
                        assertDoesNotThrow(actual::run);
                    }

                    @Test
                    @DisplayName("주문 저장소를 호출한다")
                    void verifyOnly() {
                        // given
                        Object repository = new Object();

                        // when
                        Object order = new Object();

                        // then
                        verify(repository).save(order);
                    }

                    @Disabled
                    @Test
                    @DisplayName("비활성화된 테스트는 허용하지 않는다")
                    void disabledTest() {
                        // given
                        int actual = 1;

                        // when
                        actual++;

                        // then
                        assert actual == 2;
                    }

                    @Test
                    @DisplayName("가정으로 테스트를 건너뛰지 않는다")
                    void assumptionSkipped() {
                        // given
                        int actual = 1;

                        // when
                        Assumptions.assumeTrue(false);

                        // then
                        assert actual == 1;
                    }
                }
                ''')

        List<String> violations = ClaudeConventionValidator.validate(project, new HexagonalConventionExtension())

        assert violations.any { it.contains("test method 'assertionless' must assert observable result") }
        assert violations.any { it.contains("test method 'noExceptionOnly' must not rely only on no-exception assertions") }
        assert violations.any { it.contains("test method 'verifyOnly' must not verify mocks without result/state/exception assertions") }
        assert violations.any { it.contains('tests must not use @Disabled') }
        assert violations.any { it.contains("test method 'assumptionSkipped' must not use JUnit assumptions") }
    }

    private Project sampleProject() {
        File projectDir = new File(tempDir, UUID.randomUUID().toString())
        projectDir.mkdirs()
        return ProjectBuilder.builder().withProjectDir(projectDir).build()
    }

    private static void writeJava(Project project, String path, String source) {
        File file = new File(project.projectDir, "src/main/java/${path}")
        file.parentFile.mkdirs()
        file.text = source.stripIndent().trim() + System.lineSeparator()
    }

    private static void writeApplication(Project project) {
        writeJava(project, 'com/example/TestApplication.java', '''
                package com.example;

                public class TestApplication {
                }
                ''')
    }

    private static void writeTestJava(Project project, String path, String source) {
        File file = new File(project.projectDir, "src/test/java/${path}")
        file.parentFile.mkdirs()
        file.text = source.stripIndent().trim() + System.lineSeparator()
    }
}
