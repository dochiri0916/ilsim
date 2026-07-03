package com.dochiri.convention.validator

import com.dochiri.convention.extension.HexagonalConventionExtension
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test

class HexagonalArchitectureValidatorTest {

    @Test
    void 'rejects inbound adapter depending on outbound adapter outside web package'() {
        Project project = sampleProject()
        writeJava(project, 'com/example/order/adapter/in/event/OrderPlacedEventListener.java', '''
                package com.example.order.adapter.in.event;

                import com.example.order.adapter.out.persistence.OrderJpaRepository;

                public final class OrderPlacedEventListener {
                    private final OrderJpaRepository orderJpaRepository;

                    public OrderPlacedEventListener(OrderJpaRepository orderJpaRepository) {
                        this.orderJpaRepository = orderJpaRepository;
                    }
                }
                ''')

        List<String> violations = HexagonalArchitectureValidator.validate(project, new HexagonalConventionExtension())

        assert violations.any { it.contains('adapter.in -> adapter.out forbidden') }
    }

    @Test
    void 'rejects outbound adapter depending on inbound adapter'() {
        Project project = sampleProject()
        writeJava(project, 'com/example/order/adapter/out/persistence/OrderJpaRepository.java', '''
                package com.example.order.adapter.out.persistence;

                import com.example.order.adapter.in.event.OrderPlacedEventListener;

                public final class OrderJpaRepository {
                    private final OrderPlacedEventListener orderPlacedEventListener;

                    public OrderJpaRepository(OrderPlacedEventListener orderPlacedEventListener) {
                        this.orderPlacedEventListener = orderPlacedEventListener;
                    }
                }
                ''')

        List<String> violations = HexagonalArchitectureValidator.validate(project, new HexagonalConventionExtension())

        assert violations.any { it.contains('adapter.out -> adapter.in forbidden') }
    }

    @Test
    void 'rejects application depending on inbound event adapter'() {
        Project project = sampleProject()
        writeJava(project, 'com/example/order/application/service/PlaceOrderService.java', '''
                package com.example.order.application.service;

                import com.example.order.adapter.in.event.OrderPlacedEventListener;

                public final class PlaceOrderService {
                    private final OrderPlacedEventListener orderPlacedEventListener;

                    public PlaceOrderService(OrderPlacedEventListener orderPlacedEventListener) {
                        this.orderPlacedEventListener = orderPlacedEventListener;
                    }
                }
                ''')

        List<String> violations = HexagonalArchitectureValidator.validate(project, new HexagonalConventionExtension())

        assert violations.any { it.contains('application -> adapter forbidden') }
    }

    private static Project sampleProject() {
        File projectDir = File.createTempDir('hexagonal-architecture-validator-test', '')
        return ProjectBuilder.builder().withProjectDir(projectDir).build()
    }

    private static void writeJava(Project project, String path, String source) {
        File file = new File(project.projectDir, "src/main/java/${path}")
        file.parentFile.mkdirs()
        file.text = source.stripIndent().trim() + System.lineSeparator()
    }
}
