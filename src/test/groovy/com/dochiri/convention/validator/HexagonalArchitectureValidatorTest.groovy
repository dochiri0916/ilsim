package com.dochiri.convention.validator

import com.dochiri.convention.extension.HexagonalConventionExtension
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.DisplayName
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

    @Test
    @DisplayName('Domain에서 Application과 양쪽 Adapter로 향하는 모든 의존을 거부한다')
    void rejectsEveryOutwardDependencyFromDomain() {
        // given
        Project project = sampleProject()
        writeJava(project, 'com/example/order/domain/model/Order.java', '''
                package com.example.order.domain.model;

                import com.example.order.adapter.in.scheduler.OrderScheduler;
                import com.example.order.adapter.out.persistence.OrderEntity;
                import com.example.order.application.service.PlaceOrderService;

                public final class Order {
                }
                ''')

        // when
        List<String> violations = HexagonalArchitectureValidator.validate(
                project,
                new HexagonalConventionExtension()
        )

        // then
        assert violations.count { it.contains('domain -> application/adapter forbidden') } == 3
    }

    @Test
    @DisplayName('사용자 정의 Inbound Adapter segment에서도 Outbound Adapter 의존을 거부한다')
    void supportsCustomInboundAdapterSegment() {
        // given
        Project project = sampleProject()
        writeJava(project, 'com/example/order/delivery/web/OrderController.java', '''
                package com.example.order.delivery.web;

                import com.example.order.adapter.out.persistence.OrderEntity;

                public final class OrderController {
                }
                ''')
        writeJava(project, 'com/example/order/unknown/Unclassified.java', '''
                package com.example.order.unknown;

                import com.example.order.adapter.out.persistence.OrderEntity;

                public final class Unclassified {
                }
                ''')
        HexagonalConventionExtension convention = new HexagonalConventionExtension()
        convention.presentationPackageSegment = 'delivery'

        // when
        List<String> violations = HexagonalArchitectureValidator.validate(project, convention)

        // then
        assert violations.size() == 1
        assert violations.first().contains('adapter.in -> adapter.out forbidden')
    }

    @Test
    @DisplayName('줄바꿈된 import도 Domain 의존 방향 검사를 우회하지 못한다')
    void rejectsMultilineDomainImport() {
        // given
        Project project = sampleProject()
        writeJava(project, 'com/example/order/domain/model/Order.java', '''
                package com.example.order.domain.model;

                import com.example.order
                    .application.service.PlaceOrderService;

                public final class Order {
                    private PlaceOrderService service;
                }
                ''')

        // when
        List<String> violations = HexagonalArchitectureValidator.validate(
                project,
                new HexagonalConventionExtension()
        )

        // then
        assert violations.any { it.contains('domain -> application/adapter forbidden') }
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
