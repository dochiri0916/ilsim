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

                import org.junit.jupiter.api.DisplayName;
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
                }
                ''')

        List<String> violations = ClaudeConventionValidator.validate(project, new HexagonalConventionExtension())

        assert violations.any { it.contains("test method 'assertionless' must assert observable result") }
        assert violations.any { it.contains("test method 'noExceptionOnly' must not rely only on no-exception assertions") }
        assert violations.any { it.contains("test method 'verifyOnly' must not verify mocks without result/state/exception assertions") }
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
