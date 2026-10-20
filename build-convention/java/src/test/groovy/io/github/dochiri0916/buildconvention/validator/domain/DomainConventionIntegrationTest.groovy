package io.github.dochiri0916.buildconvention.validator.domain

import io.github.dochiri0916.buildconvention.extension.HexagonalConventionExtension
import io.github.dochiri0916.buildconvention.validator.architecture.JavaSourceArchitectureValidator
import io.github.dochiri0916.buildconvention.validator.domain.factory.DomainStaticFactoryValidator
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DomainConventionIntegrationTest {
    @TempDir
    File tempDir

    @Test
    @DisplayName('문서 템플릿의 도메인과 호출자는 새 규칙을 함께 통과한다')
    void validatesTemplates() {
        def project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        File sourceRoot = new File('../docs/templates/src/main/java')
        sourceRoot.eachFileRecurse { File file ->
            if (file.name.endsWith('.java')) {
                File target = new File(tempDir, 'src/main/java/' + sourceRoot.toPath().relativize(file.toPath()))
                target.parentFile.mkdirs()
                target.text = file.text
            }
        }
        def convention = new HexagonalConventionExtension()
        convention.basePackage = 'com.example.demo'

        List<String> violations = JavaSourceArchitectureValidator.validate(project, convention)
        violations.addAll(DomainStaticFactoryValidator.validate(project, convention))

        assert violations.isEmpty()
    }

    @Test
    @DisplayName('Outbox record와 이벤트의 도메인 객체 및 메타데이터를 허용한다')
    void acceptsOutboxAndEvents() {
        def project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        write('global/domain/outbox/Delivery.java', '''
            package com.example.global.domain.outbox;
            public record Delivery(long sequence) {
                public Delivery {
                    if (sequence < 0) throw new InvalidDeliveryException();
                }
            }
        ''')
        write('order/domain/outbox/OrderOutbox.java', '''
            package com.example.order.domain.outbox;
            public record OrderOutbox(OrderId id, long revision) {
                public OrderOutbox {
                    if (id == null) throw new OrderIdRequiredException();
                }
            }
        ''')
        write('order/domain/event/OrderPlaced.java', '''
            package com.example.order.domain.event;
            import com.example.order.domain.Order;
            public record OrderPlaced(Order order) {
                public OrderPlaced {
                    if (order == null) throw new OrderRequiredException();
                }
            }
        ''')
        def convention = new HexagonalConventionExtension()
        convention.basePackage = 'com.example'

        List<String> violations = JavaSourceArchitectureValidator.validate(project, convention)

        assert violations.isEmpty()
    }

    @Test
    @DisplayName('원시 컬렉션과 ID 위치 및 이벤트 상태 보관은 안정적인 rule ID로 거부한다')
    void rejectsBypassesWithRuleIds() {
        def project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        write('order/domain/model/Order.java', '''
            package com.example.order.domain.model;
            import java.util.Collection;
            import com.example.order.domain.event.OrderPlaced;
            public final class Order {
                private final OrderId id = null;
                private final OrderPlaced lastPlaced = null;
                private final Collection<OrderLine> lines = null;
            }
        ''')
        write('order/domain/model/OrderId.java', '''
            package com.example.order.domain.model;
            public record OrderId(String value) {}
        ''')
        def convention = new HexagonalConventionExtension()
        convention.basePackage = 'com.example'

        List<String> violations = JavaSourceArchitectureValidator.validate(project, convention)

        assert violations.any { it.contains('must not retain domain events') && it.startsWith('[ARCH-DOMAIN-AGGREGATE]') }
        assert violations.any { it.contains('must live in domain.vo package') && it.startsWith('[ARCH-PACKAGE-TOPOLOGY]') }
        assert violations.any { it.contains("domain field 'lines' must use a first-class collection") }
    }

    @Test
    @DisplayName('애그리거트 팩터리와 식별자 유지 및 메타데이터 예외를 검증한다')
    void validatesAggregateLifecycle() {
        def project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        String source = """
            package com.example.order.domain.model;
            public final class Order {
                private final OrderId id;
                private final long revision;
                private Order(final OrderId id, final long revision) {
                    if (id == null) throw new OrderIdRequiredException();
                    this.id = id;
                    this.revision = revision;
                }
                public static Order create() { return new Order(OrderId.generate(), 0); }
                public static Order restore(final OrderId id, final long revision) { return new Order(id, revision); }
                public Order revise() { return new Order(id, revision + 1); }
                public boolean equals(final Object object) { return object instanceof Order other && id.equals(
                        other.id); }
                public int hashCode() { return id.hashCode(); }
            }
        """
        write('order/domain/model/Order.java', source)
        def convention = new HexagonalConventionExtension()
        convention.basePackage = 'com.example'

        List<String> violations = JavaSourceArchitectureValidator.validate(project, convention)

        assert violations.isEmpty()
        write('order/domain/model/Order.java', source.replace('new Order(id, revision + 1)', 'new Order(OrderId.generate(), revision + 1)'))
        assert JavaSourceArchitectureValidator.validate(project, convention)
                .any { it.contains('state changes must preserve its identifier') }
        write('order/domain/model/Order.java', source.replace('static Order restore', 'static Order load'))
        assert JavaSourceArchitectureValidator.validate(project, convention)
                .any { it.contains('must expose create and restore factories') }
    }

    @Test
    @DisplayName('명시적 생성자 주입도 Lombok 생성자와 같은 계약으로 허용한다')
    void accepts_explicit_constructor_injection() {
        def project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        write('order/application/port/in/usecase/PlaceOrderUseCase.java', """
            package com.example.order.application.port.in.usecase;
            @FunctionalInterface public interface PlaceOrderUseCase { void place(); }
        """)
        write('order/application/port/out/InventoryLookupPort.java', """
            package com.example.order.application.port.out;
            @FunctionalInterface public interface InventoryLookupPort { boolean exists(); }
        """)
        write('order/application/service/PlaceOrderService.java', """
            package com.example.order.application.service;
            import com.example.order.application.port.in.usecase.PlaceOrderUseCase;
            import com.example.order.application.port.out.InventoryLookupPort;
            import org.springframework.stereotype.Service;
            import org.springframework.transaction.annotation.Transactional;
            @Service public final class PlaceOrderService implements PlaceOrderUseCase {
                private final InventoryLookupPort inventoryLookupPort;
                public PlaceOrderService(final InventoryLookupPort inventoryLookupPort) {
                    this.inventoryLookupPort = inventoryLookupPort;
                }
                @Override @Transactional public void place() { inventoryLookupPort.exists(); }
            }
        """)
        def convention = new HexagonalConventionExtension()
        convention.basePackage = 'com.example'

        List<String> violations = JavaSourceArchitectureValidator.validate(project, convention)

        assert violations.isEmpty()
    }

    @Test
    @DisplayName('sealed 예외 기반 생성자는 모든 오버로드에서 protected여야 한다')
    void requires_protected_exception_base_constructors() {
        def project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        def convention = new HexagonalConventionExtension()
        convention.basePackage = 'com.example'
        String source = '''
            package com.example.order.domain.exception;
            public abstract sealed class OrderDomainException extends RuntimeException permits MissingOrderException {
                protected OrderDomainException(final String message) { super(message); }
                protected OrderDomainException(final String message, final Throwable cause) { super(message, cause); }
            }
        '''
        write('order/domain/exception/MissingOrderException.java', '''
            package com.example.order.domain.exception;
            public final class MissingOrderException extends OrderDomainException {
                public MissingOrderException() { super("주문이 필요합니다"); }
            }
        ''')
        write('order/domain/exception/OrderDomainException.java', source)
        def valid = JavaSourceArchitectureValidator.validate(project, convention)
        assert valid.empty
        ['public', 'private', ''].each { visibility ->
            write('order/domain/exception/OrderDomainException.java', source.replace(
                    'protected OrderDomainException(final String message,',
                    visibility + ' OrderDomainException(final String message,'))
            assert JavaSourceArchitectureValidator.validate(project, convention).any {
                it.contains('must keep constructors protected') && it.startsWith('[ARCH-EXCEPTION-CONTRACT]')
            }
        }
        write('order/domain/exception/OrderDomainException.java', '''
            package com.example.order.domain.exception;
            public abstract sealed class OrderDomainException extends RuntimeException permits MissingOrderException {}
        ''')
        assert JavaSourceArchitectureValidator.validate(project, convention).any { it.contains('must keep constructors protected') }
    }

    @Test
    @DisplayName('permits의 누락·가짜 자식·다른 Context 부모와 중첩 예외 우회를 거부한다')
    void rejects_incomplete_or_foreign_exception_hierarchies() {
        def project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        def convention = new HexagonalConventionExtension(basePackage: 'com.example')
        String base = '''
            package com.example.order.domain.exception;
            public abstract sealed class OrderDomainException extends RuntimeException permits MissingOrderException {
                protected OrderDomainException(final String message) { super(message); }
            }
        '''
        write('order/domain/exception/OrderDomainException.java', base)
        write('order/domain/exception/MissingOrderException.java', '''
            package com.example.order.domain.exception;
            public final class MissingOrderException extends OrderDomainException {
                public MissingOrderException() { super("주문이 필요합니다"); }
            }
        ''')
        assert JavaSourceArchitectureValidator.validate(project, convention).empty
        write('order/domain/exception/OrderDomainException.java', base.replace('permits MissingOrderException', 'permits UnknownOrderException'))
        def incomplete = JavaSourceArchitectureValidator.validate(project, convention)
        assert incomplete.any { it.contains('permits must include concrete exception') }
        assert incomplete.any { it.contains('permits must name existing final direct exceptions') }
        write('order/domain/exception/OrderDomainException.java', base.replace('extends RuntimeException', 'extends example.fake.RuntimeException'))
        assert JavaSourceArchitectureValidator.validate(project, convention).any {
            it.contains("exception base 'OrderDomainException' must be abstract sealed")
        }
        write('order/domain/exception/OrderDomainException.java', base)
        write('order/domain/exception/Failures.java', '''
            package com.example.order.domain.exception;
            public final class Failures {
                public static final class SneakyException extends RuntimeException {}
            }
        ''')
        assert JavaSourceArchitectureValidator.validate(project, convention).any {
            it.contains("concrete domain exception 'SneakyException' must extend its own context")
        }
        write('payment/domain/exception/PaymentDomainException.java', '''
            package com.example.payment.domain.exception;
            public abstract sealed class PaymentDomainException extends RuntimeException permits com.example.order.domain.exception.MissingOrderException {
                protected PaymentDomainException(final String message) { super(message); }
            }
        ''')
        write('order/domain/exception/MissingOrderException.java', '''
            package com.example.order.domain.exception;
            import com.example.payment.domain.exception.PaymentDomainException;
            public final class MissingOrderException extends PaymentDomainException {
                public MissingOrderException() { super("주문이 필요합니다"); }
            }
        ''')
        assert JavaSourceArchitectureValidator.validate(project, convention).any {
            it.contains("concrete domain exception 'MissingOrderException' must extend its own context")
        }
    }

    @Test
    @DisplayName('공통 VO 예외의 기반은 GlobalDomainException으로 고정한다')
    void requires_global_domain_exception_base() {
        def project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        def convention = new HexagonalConventionExtension(basePackage: 'com.example')
        String base = '''
            package com.example.global.domain.exception;
            public abstract sealed class GlobalDomainException extends RuntimeException permits InvalidMoneyException {
                protected GlobalDomainException(final String message) { super(message); }
            }
        '''
        String child = '''
            package com.example.global.domain.exception;
            public final class InvalidMoneyException extends GlobalDomainException {
                public InvalidMoneyException() { super("금액이 올바르지 않습니다"); }
            }
        '''
        write('global/domain/exception/GlobalDomainException.java', base)
        write('global/domain/exception/InvalidMoneyException.java', child)
        assert JavaSourceArchitectureValidator.validate(project, convention).empty
        new File(tempDir, 'src/main/java/com/example/global/domain/exception/GlobalDomainException.java').delete()
        write('global/domain/exception/SharedDomainException.java', base.replace('GlobalDomainException', 'SharedDomainException'))
        write('global/domain/exception/InvalidMoneyException.java', child.replace('GlobalDomainException', 'SharedDomainException'))
        def violations = JavaSourceArchitectureValidator.validate(project, convention)
        assert violations.any { it.contains('shared VO exception base must be named GlobalDomainException') }
        assert violations.any { it.contains("concrete domain exception 'InvalidMoneyException'") }
    }

    private void write(String path, String source) {
        File file = new File(tempDir, "src/main/java/com/example/${path}")
        file.parentFile.mkdirs()
        file.text = source.stripIndent()
    }
}
