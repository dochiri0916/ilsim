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
    void 'rejects exception architecture violations'() {
        Project project = sampleProject()
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

    private static void writeTestJava(Project project, String path, String source) {
        File file = new File(project.projectDir, "src/test/java/${path}")
        file.parentFile.mkdirs()
        file.text = source.stripIndent().trim() + System.lineSeparator()
    }
}
