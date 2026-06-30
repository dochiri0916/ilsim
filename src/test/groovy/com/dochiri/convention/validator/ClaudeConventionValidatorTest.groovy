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
}
