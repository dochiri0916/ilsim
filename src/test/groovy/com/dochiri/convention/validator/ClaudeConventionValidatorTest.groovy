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
    void 'rejects quality tool suppress warnings in production code'() {
        Project project = sampleProject()
        writeApplication(project)
        writeJava(project, 'com/example/member/adapter/out/persistence/MemberEntity.java', '''
                package com.example.member.adapter.out.persistence;

                import jakarta.persistence.Entity;
                import jakarta.persistence.GeneratedValue;
                import jakarta.persistence.GenerationType;
                import jakarta.persistence.Id;
                import jakarta.persistence.Table;
                import lombok.AccessLevel;
                import lombok.Getter;
                import lombok.NoArgsConstructor;

                @Entity
                @Table(name = "members")
                @Getter
                @NoArgsConstructor(access = AccessLevel.PROTECTED)
                @SuppressWarnings("PMD.ImmutableField")
                public class MemberEntity {

                    @Getter(AccessLevel.NONE)
                    @Id
                    @GeneratedValue(strategy = GenerationType.IDENTITY)
                    private Long id;
                }
                ''')
        writeJava(project, 'com/example/order/application/service/OrderService.java', '''
                package com.example.order.application.service;

                @edu.umd.cs.findbugs.annotations.SuppressFBWarnings("EI_EXPOSE_REP2")
                public final class OrderService {
                }
                ''')

        List<String> violations = ClaudeConventionValidator.validate(project, new HexagonalConventionExtension())

        assert violations.count { it.contains('must not suppress PMD/Checkstyle/SpotBugs warnings') } == 2
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
        writeJava(project, 'com/example/global/web/ApiWebConfig.java', '''
                package com.example.global.web;

                import lombok.RequiredArgsConstructor;
                import org.springframework.context.annotation.Configuration;

                @Configuration
                @RequiredArgsConstructor
                public final class ApiWebConfig {
                }
                ''')

        List<String> violations = ClaudeConventionValidator.validate(project, new HexagonalConventionExtension())

        assert !violations.any { it.contains('package must follow {context}/domain') }
        assert !violations.any { it.contains('root package may contain only') }
        assert !violations.any { it.contains('global package must be limited') }
    }

    @Test
    void 'does not treat plain global error exceptions as Spring components'() {
        Project project = sampleProject()
        writeApplication(project)
        writeJava(project, 'com/example/global/error/AuthenticationRequiredException.java', '''
                package com.example.global.error;

                public final class AuthenticationRequiredException extends RuntimeException {
                    private static final long serialVersionUID = 1L;
                    private final GlobalErrorCode errorCode;

                    private AuthenticationRequiredException(final GlobalErrorCode errorCode) {
                        this.errorCode = errorCode;
                    }

                    public static AuthenticationRequiredException authenticationRequired() {
                        return new AuthenticationRequiredException(GlobalErrorCode.AUTHENTICATION_REQUIRED);
                    }
                }
                ''')
        writeJava(project, 'com/example/global/error/GlobalErrorCode.java', '''
                package com.example.global.error;

                public enum GlobalErrorCode {
                    AUTHENTICATION_REQUIRED
                }
                ''')

        List<String> violations = ClaudeConventionValidator.validate(project, new HexagonalConventionExtension())

        assert !violations.any { it.contains('with final dependencies must declare @RequiredArgsConstructor') }
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
    void 'rejects MessageSource bundles and Value injection'() {
        Project project = sampleProject()
        writeApplication(project)
        writeResource(project, 'messages/messages.properties', '''
                error.authentication.required=인증이 필요합니다.
                ''')
        writeJava(project, 'com/example/global/error/ApiProblemMessageResolver.java', '''
                package com.example.global.error;

                import org.springframework.beans.factory.annotation.Value;
                import org.springframework.context.MessageSource;

                public final class ApiProblemMessageResolver {

                    private final MessageSource messageSource;

                    @Value("${api.problem.type-prefix}")
                    private String typePrefix;
                }
                ''')

        List<String> violations = ClaudeConventionValidator.validate(project, new HexagonalConventionExtension())

        assert violations.any { it.contains('messages/messages.properties must not use MessageSource message bundle resources') }
        assert violations.any { it.contains('must not use MessageSource') }
        assert violations.any { it.contains('must not use @Value') }
    }

    @Test
    void 'rejects non-namespaced API error code keys'() {
        Project project = sampleProject()
        writeApplication(project)
        writeJava(project, 'com/example/member/application/exception/MemberErrorCode.java', '''
                package com.example.member.application.exception;

                public enum MemberErrorCode {
                    DUPLICATE_EMAIL
                }
                ''')
        writeJava(project, 'com/example/member/adapter/in/web/MemberErrorCodeMappingProvider.java', '''
                package com.example.member.adapter.in.web;

                import com.example.global.error.ApiExceptionMapper;
                import com.example.global.error.ErrorCodeMappingProvider;
                import com.example.member.application.exception.MemberErrorCode;
                import java.util.Map;

                public final class MemberErrorCodeMappingProvider implements ErrorCodeMappingProvider {

                    public Map<String, ApiExceptionMapper.Mapping> errorCodeMappings() {
                        return Map.of(MemberErrorCode.DUPLICATE_EMAIL.name(), null);
                    }
                }
                ''')
        writeJava(project, 'com/example/member/adapter/in/web/MemberErrorMessageProvider.java', '''
                package com.example.member.adapter.in.web;

                import com.example.global.error.ApiErrorMessage;
                import com.example.global.error.ApiErrorMessageProvider;
                import com.example.member.application.exception.MemberErrorCode;
                import java.util.Map;

                  public final class MemberErrorMessageProvider implements ApiErrorMessageProvider {

                      public Map<String, ApiErrorMessage> errorMessages() {
                          return Map.of("MEMBER.DUPLICATE_EMAIL", new ApiErrorMessage("중복", "중복입니다."));
                      }
                  }
                  ''')

          List<String> violations = ClaudeConventionValidator.validate(project, new HexagonalConventionExtension())

          assert violations.any { it.contains('must use ApiErrorCode.from(errorCode), not Enum.name()') }
          assert violations.any { it.contains('must use ApiErrorCode.from(errorCode), not hard-coded string literals') }
      }

      @Test
      void 'rejects raw command value repository lookups before VO normalization'() {
        Project project = sampleProject()
        writeApplication(project)
        writeJava(project, 'com/example/member/application/port/in/RegisterMemberCommand.java', '''
                package com.example.member.application.port.in;

                public record RegisterMemberCommand(String email) {
                }
                ''')
        writeJava(project, 'com/example/member/application/port/in/RegisterMemberUseCase.java', '''
                package com.example.member.application.port.in;

                public interface RegisterMemberUseCase {
                    void register(RegisterMemberCommand command);
                }
                ''')
        writeJava(project, 'com/example/member/application/port/out/MemberRepositoryPort.java', '''
                package com.example.member.application.port.out;

                public interface MemberRepositoryPort {
                    boolean existsByEmail(String email);
                }
                ''')
        writeJava(project, 'com/example/member/application/service/RegisterMemberService.java', '''
                package com.example.member.application.service;

                import com.example.member.application.port.in.RegisterMemberCommand;
                import com.example.member.application.port.in.RegisterMemberUseCase;
                import com.example.member.application.port.out.MemberRepositoryPort;
                import lombok.RequiredArgsConstructor;
                import org.springframework.stereotype.Service;
                import org.springframework.transaction.annotation.Transactional;

                @Service
                @RequiredArgsConstructor
                public final class RegisterMemberService implements RegisterMemberUseCase {

                    private final MemberRepositoryPort memberRepositoryPort;

                    @Override
                    @Transactional
                    public void register(final RegisterMemberCommand command) {
                        memberRepositoryPort.existsByEmail(command.email());
                    }
                }
                ''')

        List<String> violations = ClaudeConventionValidator.validate(project, new HexagonalConventionExtension())

          assert violations.any { it.contains('must create a VO and pass normalized vo.value() to repository exists/find calls') }
      }

      @Test
      void 'rejects direct cross context aggregate references in domain model'() {
          Project project = sampleProject()
          writeApplication(project)
          writeJava(project, 'com/example/member/domain/model/Member.java', '''
                  package com.example.member.domain.model;

                  public record Member(MemberId id) {

                      public Member {
                          if (id == null) {
                              throw new IllegalArgumentException();
                          }
                      }
                  }
                  ''')
          writeJava(project, 'com/example/member/domain/model/MemberId.java', '''
                  package com.example.member.domain.model;

                  public record MemberId(String value) {

                      public MemberId {
                          if (value == null) {
                              throw new IllegalArgumentException();
                          }
                          if (value.isBlank()) {
                              throw new IllegalArgumentException();
                          }
                      }

                      public static MemberId generate() {
                          return new MemberId("member-id");
                      }
                  }
                  ''')
          writeJava(project, 'com/example/order/domain/model/Order.java', '''
                  package com.example.order.domain.model;

                  import com.example.member.domain.model.Member;

                  public record Order(OrderId id, Member member) {

                      public Order {
                          if (id == null || member == null) {
                              throw new IllegalArgumentException();
                          }
                      }
                  }
                  ''')
          writeJava(project, 'com/example/order/domain/model/OrderId.java', '''
                  package com.example.order.domain.model;

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

          List<String> violations = ClaudeConventionValidator.validate(project, new HexagonalConventionExtension())

          assert violations.any { it.contains("must reference other aggregate 'Member' by identifier VO") }
      }

      @Test
      void 'rejects modifying multiple aggregate repositories in one transaction'() {
          Project project = sampleProject()
          writeApplication(project)
          writeJava(project, 'com/example/order/application/port/in/PlaceOrderUseCase.java', '''
                  package com.example.order.application.port.in;

                  public interface PlaceOrderUseCase {
                      void place();
                  }
                  ''')
          writeJava(project, 'com/example/order/application/port/out/OrderRepositoryPort.java', '''
                  package com.example.order.application.port.out;

                  public interface OrderRepositoryPort {
                      void save();
                  }
                  ''')
          writeJava(project, 'com/example/order/application/port/out/CartRepositoryPort.java', '''
                  package com.example.order.application.port.out;

                  public interface CartRepositoryPort {
                      void delete();
                  }
                  ''')
          writeJava(project, 'com/example/order/application/service/PlaceOrderService.java', '''
                  package com.example.order.application.service;

                  import com.example.order.application.port.in.PlaceOrderUseCase;
                  import com.example.order.application.port.out.CartRepositoryPort;
                  import com.example.order.application.port.out.OrderRepositoryPort;
                  import lombok.RequiredArgsConstructor;
                  import org.springframework.stereotype.Service;
                  import org.springframework.transaction.annotation.Transactional;

                  @Service
                  @RequiredArgsConstructor
                  public final class PlaceOrderService implements PlaceOrderUseCase {

                      private final OrderRepositoryPort orderRepositoryPort;
                      private final CartRepositoryPort cartRepositoryPort;

                      @Override
                      @Transactional
                      public void place() {
                          orderRepositoryPort.save();
                          cartRepositoryPort.delete();
                      }
                  }
                  ''')

          List<String> violations = ClaudeConventionValidator.validate(project, new HexagonalConventionExtension())

          assert violations.any { it.contains("application service method 'place' must not modify multiple aggregate repositories") }
      }

      @Test
      void 'rejects path based public API exclusions and nullable authenticated member resolver'() {
        Project project = sampleProject()
        writeApplication(project)
        writeJava(project, 'com/example/global/web/ApiWebConfig.java', '''
                package com.example.global.web;

                import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
                import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

                public final class ApiWebConfig implements WebMvcConfigurer {

                    public void addInterceptors(final InterceptorRegistry registry) {
                        registry.addInterceptor(null)
                                .addPathPatterns("/api/**")
                                .excludePathPatterns("/api/members/login");
                    }
                }
                ''')
        writeJava(project, 'com/example/global/web/AuthenticatedMemberArgumentResolver.java', '''
                package com.example.global.web;

                  import org.springframework.web.method.support.HandlerMethodArgumentResolver;

                  public final class AuthenticatedMemberArgumentResolver implements HandlerMethodArgumentResolver {

                      public boolean supportsParameter(final Object parameter) {
                          return parameter.hasParameterAnnotation(AuthenticatedMember.class);
                      }

                      public Object resolveArgument() {
                          return null;
                      }
                  }
                ''')

        List<String> violations = ClaudeConventionValidator.validate(project, new HexagonalConventionExtension())

        assert violations.any { it.contains('must mark public APIs with @PublicApi') }
        assert violations.any { it.contains('@AuthenticatedMember resolver must throw an authentication exception') }
    }

    @Test
    void 'rejects wildcard imports'() {
        Project project = sampleProject()
        writeApplication(project)
        writeJava(project, 'com/example/order/domain/model/Order.java', '''
                package com.example.order.domain.model;

                import java.util.*;
                import static java.util.Objects.*;

                public final class Order {
                }
                ''')
        writeTestJava(project, 'com/example/order/OrderTest.java', '''
                package com.example.order;

                import java.time.*;

                class OrderTest {
                }
                ''')

        List<String> violations = ClaudeConventionValidator.validate(project, new HexagonalConventionExtension())

        assert violations.any { it.contains("must not use wildcard import 'java.util.*'") }
        assert violations.any { it.contains("must not use wildcard import 'java.util.Objects.*'") }
        assert violations.any { it.contains("must not use wildcard import 'java.time.*'") }
    }

    @Test
    void 'rejects domain invariant exceptions without ErrorCode factories'() {
        Project project = sampleProject()
        writeApplication(project)
        writeJava(project, 'com/example/member/domain/exception/InvalidMemberIdException.java', '''
                package com.example.member.domain.exception;

                public final class InvalidMemberIdException extends RuntimeException {

                    private static final long serialVersionUID = 1L;

                    public InvalidMemberIdException(String message) {
                        super(message);
                    }
                }
                ''')
        writeJava(project, 'com/example/member/domain/model/MemberId.java', '''
                package com.example.member.domain.model;

                import com.example.member.domain.exception.InvalidMemberIdException;

                import static java.util.Objects.requireNonNull;

                public record MemberId(String value) {

                    public MemberId {
                        requireNonNull(value, "회원 ID는 null일 수 없습니다.");
                        value = value.strip();
                        if (value.isBlank()) {
                            throw new InvalidMemberIdException("회원 ID는 공백일 수 없습니다.");
                        }
                    }
                }
                ''')

        List<String> violations = ClaudeConventionValidator.validate(project, new HexagonalConventionExtension())

        assert violations.any { it.contains("domain exception 'InvalidMemberIdException' must keep constructors private") }
        assert violations.any { it.contains("domain exception 'InvalidMemberIdException' must expose at least one static factory") }
        assert violations.any { it.contains('domain invariants must not use requireNonNull') }
        assert violations.any { it.contains('domain must raise exceptions through static factory methods') }
        assert violations.any { it.contains('domain must use ErrorCode-based exceptions') }
    }

    @Test
    void 'rejects application exceptions created with string messages'() {
        Project project = sampleProject()
        writeApplication(project)
        writeJava(project, 'com/example/order/application/exception/EmptyCartException.java', '''
                package com.example.order.application.exception;

                public final class EmptyCartException extends RuntimeException {

                    private static final long serialVersionUID = 1L;

                    public EmptyCartException(String message) {
                        super(message);
                    }
                }
                ''')
        writeJava(project, 'com/example/order/application/service/PlaceOrderService.java', '''
                package com.example.order.application.service;

                import com.example.order.application.exception.EmptyCartException;

                public final class PlaceOrderService {

                    public void place() {
                        throw new EmptyCartException("장바구니가 비어 있습니다.");
                    }
                }
                ''')

        List<String> violations = ClaudeConventionValidator.validate(project, new HexagonalConventionExtension())

        assert violations.any { it.contains("application exception 'EmptyCartException' must keep constructors private") }
        assert violations.any { it.contains("application exception 'EmptyCartException' must expose at least one static factory") }
        assert violations.any { it.contains('application must raise exceptions through static factory methods') }
        assert violations.any { it.contains('application must use ErrorCode-based exceptions') }
    }

    @Test
    void 'rejects exception detail fields that match accessors'() {
        Project project = sampleProject()
        writeApplication(project)
        writeJava(project, 'com/example/member/application/exception/MemberApplicationErrorCode.java', '''
                package com.example.member.application.exception;

                public enum MemberApplicationErrorCode {
                    DUPLICATE_EMAIL
                }
                ''')
        writeJava(project, 'com/example/member/application/exception/DuplicateEmailException.java', '''
                package com.example.member.application.exception;

                public final class DuplicateEmailException extends RuntimeException {

                    private static final long serialVersionUID = 1L;

                    private final MemberApplicationErrorCode errorCode;
                    private final String email;

                    private DuplicateEmailException(final MemberApplicationErrorCode errorCode, final String email) {
                        super(errorCode.name());
                        this.errorCode = errorCode;
                        this.email = email;
                    }

                    public static DuplicateEmailException duplicated(final String email) {
                        return new DuplicateEmailException(MemberApplicationErrorCode.DUPLICATE_EMAIL, email);
                    }

                    public MemberApplicationErrorCode code() {
                        return errorCode;
                    }

                    public String email() {
                        return email;
                    }
                }
                ''')

        List<String> violations = ClaudeConventionValidator.validate(project, new HexagonalConventionExtension())

        assert violations.any { it.contains("exception detail field 'email' must use a context-specific internal name") }
    }

    @Test
    void 'rejects Spring Security dependency, generic DTOs, manual MVC extension wiring, and English exception messages'() {
        Project project = sampleProject()
        writeApplication(project)
        writeJava(project, 'com/example/member/application/service/RegisterMemberService.java', '''
                package com.example.member.application.service;

                import org.springframework.security.crypto.password.PasswordEncoder;

                public final class RegisterMemberService {

                    private final PasswordEncoder passwordEncoder;

                    RegisterMemberService(final PasswordEncoder passwordEncoder) {
                        this.passwordEncoder = passwordEncoder;
                    }
                }
                ''')
        writeJava(project, 'com/example/member/adapter/in/web/WebConfig.java', '''
                package com.example.member.adapter.in.web;

                import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
                import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

                public final class WebConfig implements WebMvcConfigurer {

                    @Override
                    public void addInterceptors(final InterceptorRegistry registry) {
                        registry.addInterceptor(new LoginCheckInterceptor());
                    }
                }
                ''')
        writeJava(project, 'com/example/member/adapter/in/web/response/MemberResponse.java', '''
                package com.example.member.adapter.in.web.response;

                public final class MemberResponse {
                }
                ''')
        writeJava(project, 'com/example/member/adapter/out/security/RuntimeGuard.java', '''
                package com.example.member.adapter.out.security;

                public final class RuntimeGuard {

                    void guard() {
                        throw new IllegalStateException("invalid password");
                    }
                }
                ''')

        List<String> violations = ClaudeConventionValidator.validate(project, new HexagonalConventionExtension())

        assert violations.any { it.contains('application must depend on a password port') }
        assert violations.any { it.contains('web configuration must inject Interceptor/ArgumentResolver components') }
        assert violations.any { it.contains("API DTO 'MemberResponse' must be a record") }
        assert violations.any { it.contains("API DTO 'MemberResponse' must be responsibility-specific") }
        assert violations.any { it.contains('exception message string literals must be written in Korean') }
    }

    @Test
    void 'keeps JPA domain identifier naming as domain id instead of public id'() {
        Project project = sampleProject()
        writeApplication(project)
        writeJava(project, 'com/example/member/adapter/out/persistence/MemberEntity.java', '''
                package com.example.member.adapter.out.persistence;

                import jakarta.persistence.Column;
                import jakarta.persistence.Entity;
                import jakarta.persistence.Id;
                import lombok.AccessLevel;
                import lombok.Getter;
                import lombok.NoArgsConstructor;

                @Entity
                @Getter
                @NoArgsConstructor(access = AccessLevel.PROTECTED)
                public class MemberEntity {

                    @Getter(AccessLevel.NONE)
                    @Id
                    private Long id;

                    @Column(nullable = false, unique = true, length = 32)
                    private String memberId;

                    @Column(nullable = false, length = 32)
                    private String orderId;

                    private MemberEntity(final Long id, final String memberId, final String orderId) {
                        this.id = id;
                        this.memberId = memberId;
                        this.orderId = orderId;
                    }

                    static MemberEntity create(final String memberId, final String orderId) {
                        return new MemberEntity(null, memberId, orderId);
                    }
                }
                ''')
        writeJava(project, 'com/example/order/adapter/out/persistence/OrderEntity.java', '''
                package com.example.order.adapter.out.persistence;

                import jakarta.persistence.Column;
                import jakarta.persistence.Entity;
                import jakarta.persistence.Id;
                import lombok.AccessLevel;
                import lombok.Getter;
                import lombok.NoArgsConstructor;

                @Entity
                @Getter
                @NoArgsConstructor(access = AccessLevel.PROTECTED)
                public class OrderEntity {

                    @Getter(AccessLevel.NONE)
                    @Id
                    private Long id;

                    @Column(nullable = false, unique = true, length = 32)
                    private String publicId;

                    private OrderEntity(final Long id, final String publicId) {
                        this.id = id;
                        this.publicId = publicId;
                    }

                    static OrderEntity create(final String publicId) {
                        return new OrderEntity(null, publicId);
                    }
                }
                ''')

        List<String> violations = ClaudeConventionValidator.validate(project, new HexagonalConventionExtension())

        assert !violations.any { it.contains("JPA entity 'MemberEntity' must declare private String memberId") }
        assert !violations.any { it.contains("JPA reference field 'orderId' must store identifier VO value as String") }
        assert violations.any { it.contains("JPA entity 'OrderEntity' must declare private String orderId") }
        assert violations.any { it.contains("JPA reference field 'publicId' must use '{target}Id' naming") }
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

    private static void writeResource(Project project, String path, String source) {
        File file = new File(project.projectDir, "src/main/resources/${path}")
        file.parentFile.mkdirs()
        file.text = source.stripIndent().trim() + System.lineSeparator()
    }
}
