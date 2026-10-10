package io.github.dochiri0916.buildconvention.validator.architecture

import io.github.dochiri0916.buildconvention.context.BoundedContexts
import io.github.dochiri0916.buildconvention.extension.HexagonalConventionExtension
import io.github.dochiri0916.buildconvention.source.java.JavaSourceAstInspector
import io.github.dochiri0916.buildconvention.source.java.SourceTypeIndex
import io.github.dochiri0916.buildconvention.validator.adapter.web.WebConversionFlowValidator
import io.github.dochiri0916.buildconvention.validator.messaging.MessagingConventionValidator
import io.github.dochiri0916.buildconvention.validator.quality.DeclarationConventionValidator
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ConventionCompletionTest {
    @TempDir File directory

    @Test
    @DisplayName('설정 record를 요구하고 FQN과 가짜 어노테이션을 구분한다')
    void requires_configuration_records() {
        // given
        write('global/configuration/Settings.java', '''
            import org.springframework.boot.context.properties.ConfigurationProperties;
            @ConfigurationProperties(prefix="app") public record Settings(String name) {}
        ''')
        // when & then
        assert declarations().empty
        write('global/configuration/Settings.java', '''
            @org.springframework.boot.context.properties.ConfigurationProperties(prefix="app") public final class Settings {}
        ''')
        assert declarations().any { it.contains('must be a record') }
        write('global/configuration/Settings.java', 'import fake.ConfigurationProperties; @ConfigurationProperties public final class Settings {}')
        assert declarations().empty
    }

    @Test
    @DisplayName('일반·중첩·상속 함수형 인터페이스를 검사하며 Object와 default는 제외한다')
    void validates_inherited_functional_interfaces() {
        // given
        write('global/messaging/Parent.java', '@FunctionalInterface public interface Parent<T> { T run(T value); }')
        write('global/messaging/Child.java', 'public interface Child extends Parent<String> { boolean equals(Object value); }')
        // when & then
        assert declarations().any { it.contains("interface 'Child'") }
        write('global/messaging/Child.java', '@FunctionalInterface public interface Child extends Parent<String> { String run(String value); }')
        assert declarations().empty
        write('global/messaging/Child.java', 'public interface Child extends Parent<String> { default String run(String value) { return value; } }')
        assert declarations().empty
        write('global/messaging/Child.java', 'public interface Child extends Parent<String> { void stop(); }')
        assert declarations().empty
        write('global/messaging/Child.java', 'public interface Child extends java.lang.Runnable {}')
        assert declarations().any { it.contains("interface 'Child'") }
        write('global/messaging/Child.java', 'public interface Child { interface Nested { void run(); } }')
        assert declarations().any { it.contains("interface 'Nested'") }
        write('global/messaging/Child.java', 'import fake.FunctionalInterface; @FunctionalInterface public interface Child { void run(); }')
        assert declarations().any { it.contains("interface 'Child'") }
    }

    @Test
    @DisplayName('이벤트 버전·식별자·자체 Mapper·발행 Port를 검사한다')
    void validates_event_pipeline_contracts() {
        // given
        write('order/application/OrderEventMapper.java', '''
            import com.example.order.application.port.out.event.OrderChanged;
            import com.example.order.domain.event.OrderPlaced;
            public final class OrderEventMapper { public static OrderChanged toEvent(final OrderPlaced event) { return new OrderChanged(event.id()); } }
        ''')
        write('order/application/port/out/IntegrationEventPort.java', '''
            import com.example.order.application.port.out.event.OrderChanged;
            @FunctionalInterface public interface IntegrationEventPort { void publish(OrderChanged event); }
        ''')
        String event = 'public record OrderChanged(String aggregateId) { public String eventType() { return "order.changed.v1"; } }'
        write('order/application/port/out/event/OrderChanged.java', event)
        // when & then
        assert messaging().empty
        write('order/application/port/out/event/OrderChanged.java', event.replace('order.changed.v1', 'OrderChanged'))
        assert messaging().any { it.contains('versioned eventType') }
        write('order/application/port/out/event/OrderChanged.java', event.replace('String aggregateId', 'long aggregateId'))
        assert messaging().any { it.contains('String aggregateId') }
        write('order/application/port/out/event/OrderChanged.java', event)
        file('order/application/OrderEventMapper.java').delete()
        file('order/application/port/out/IntegrationEventPort.java').delete()
        assert messaging().any { it.contains('requires an EventMapper') }
        assert messaging().any { it.contains('requires its own') }
    }

    @Test
    @DisplayName('Envelope 필수 필드와 타입을 검사하며 주석으로 우회하지 못한다')
    void validates_envelope_fields() {
        // given
        String envelope = 'public record MessageEnvelope(String eventId, String source, String eventType, String aggregateId, long sequence, java.time.Instant occurredAt, String payload) {}'
        write('global/messaging/MessageEnvelope.java', envelope)
        // when & then
        assert messaging().empty
        write('global/messaging/MessageEnvelope.java', envelope.replace('long sequence,', '/* long sequence, */'))
        assert messaging().any { it.contains('MessageEnvelope') }
        write('global/messaging/MessageEnvelope.java', envelope.replace('java.time.Instant', 'String'))
        assert messaging().any { it.contains('MessageEnvelope') }
    }

    @Test
    @DisplayName('상속 메시지 키와 Outbox 단일·Inbox 복합 제약을 검사한다')
    void validates_inherited_message_storage_keys() {
        // given
        write('global/messaging/persistence/OutboxEntry.java', '''
            import jakarta.persistence.*;
            @MappedSuperclass public abstract class OutboxEntry {
                @Column(name="event_id", nullable=false, length=36) private String eventId;
            }
        ''')
        String outbox = '''
            import jakarta.persistence.*;
            import com.example.global.messaging.persistence.OutboxEntry;
            @Entity @Table(name="order_outbox", uniqueConstraints=@UniqueConstraint(name="uk_order_outbox_event_id", columnNames="event_id"))
            public class OrderOutboxEntry extends OutboxEntry {}
        '''
        write('order/adapter/out/persistence/outbox/OrderOutboxEntry.java', outbox)
        // when & then
        assert messaging().empty
        write('order/adapter/out/persistence/outbox/OrderOutboxEntry.java', outbox.replace('columnNames="event_id"', 'columnNames={"event_id","source"}'))
        assert messaging().any { it.contains('singleton UNIQUE') }
        write('order/adapter/out/persistence/outbox/OrderOutboxEntry.java', outbox)
        String inbox = '''
            import jakarta.persistence.*;
            @Entity @Table(name="order_inbox", uniqueConstraints=@UniqueConstraint(name="uk_order_inbox", columnNames={"consumer","event_id"}))
            public class OrderInboxEntry {
                @Column(name="event_id", nullable=false, length=36) private String eventId;
                @Column(nullable=false) private String consumer;
            }
        '''
        write('order/adapter/out/persistence/inbox/OrderInboxEntry.java', inbox)
        assert messaging().empty
        write('order/adapter/out/persistence/inbox/OrderInboxEntry.java', inbox.replace('{"consumer","event_id"}', '{"event_id","source"}'))
        assert messaging().any { it.contains('exact UNIQUE') }
        file('global/messaging/persistence/OutboxEntry.java').text = file('global/messaging/persistence/OutboxEntry.java').text.replace('length=36', 'length=32')
        assert messaging().any { it.contains('length = 36') }
        write('order/adapter/out/persistence/outbox/OrderOutboxEntry.java', outbox.replace('{}', '{ @OneToMany private java.util.List<OrderEntity> rows; }'))
        assert messaging().any { it.contains('object relation mappings') }
    }

    @Test
    @DisplayName('타 Context 참조는 소비자의 공개 이벤트만 허용하고 전송 기술을 분리한다')
    void enforces_messaging_context_boundary() {
        // given
        write('order/adapter/in/messaging/OrderConsumer.java', 'import com.example.cart.application.port.out.event.CartChanged; public final class OrderConsumer {}')
        // when & then
        assert messaging().empty
        write('order/adapter/in/messaging/OrderConsumer.java', 'import com.example.cart.application.port.out.CartRepositoryPort; public final class OrderConsumer {}')
        assert messaging().any { it.contains('cross-context adapter dependency') }
        write('order/adapter/in/messaging/OrderConsumer.java', 'public final class OrderConsumer { @org.springframework.context.event.EventListener public void accept() {} }')
        assert messaging().any { it.contains('separate receiving transport') }
    }

    @Test
    @DisplayName('관련 계약의 변환을 요구하되 계약 없는 DTO에는 빈 계약을 강제하지 않는다')
    void requires_existing_web_contract_conversion() {
        // given
        write('order/application/port/in/command/CreateOrderCommand.java', 'public record CreateOrderCommand(String name) {}')
        write('order/adapter/in/web/request/CreateOrderRequest.java', 'public record CreateOrderRequest(String name) {}')
        write('order/adapter/in/web/response/PingResponse.java', 'public record PingResponse(String value) {}')
        // when & then
        assert web().size() == 1
        assert web().first().contains('must provide toCommand')
        write('order/adapter/in/web/request/CreateOrderRequest.java', '''
            import com.example.order.application.port.in.command.CreateOrderCommand;
            public record CreateOrderRequest(String name) { public CreateOrderCommand toCommand() { return new CreateOrderCommand(name); } }
        ''')
        assert web().empty
        write('order/adapter/in/web/request/CreateOrderRequest.java', 'public record CreateOrderRequest(String name) {}')
        write('order/adapter/in/web/CreateOrderMapper.java', '''
            import com.example.order.adapter.in.web.request.CreateOrderRequest;
            import com.example.order.application.port.in.command.CreateOrderCommand;
            public final class CreateOrderMapper {
                public static CreateOrderCommand toCommand(final CreateOrderRequest request) { return new CreateOrderCommand(request.name()); }
            }
        ''')
        assert web().empty
    }

    @Test
    @DisplayName('DTO 변환의 UseCase 호출은 지역 헬퍼에 숨겨도 거부한다')
    void rejects_service_calls_in_web_dto() {
        // given
        write('order/adapter/in/web/request/CreateOrderRequest.java', '''
            import com.example.order.application.port.in.usecase.CreateOrderUseCase;
            public record CreateOrderRequest(String name) { private void helper(final CreateOrderUseCase useCase) { useCase.execute(); } }
        ''')
        // when & then
        assert web().any { it.contains('must not call services') }
    }

    @Test
    @DisplayName('실제 진입점에서 새 선언 검사를 안정적인 rule ID로 실행한다')
    void wires_declaration_checks_with_rule_ids() {
        // given
        write('App.java', 'public class App {}')
        write('global/configuration/Settings.java', '@org.springframework.boot.context.properties.ConfigurationProperties(prefix="app") public final class Settings {}')
        // when
        def violations = JavaSourceArchitectureValidator.validate(project(), new HexagonalConventionExtension(basePackage: 'com.example'))
        // then
        assert violations.any { it.startsWith('[ARCH-CODE-STYLE]') && it.contains('must be a record') }
    }

    @Test
    @DisplayName('사용하지 않는 변환으로 컨트롤러의 직접 Command 조립을 숨기지 못한다')
    void validates_controller_conversion_flow() {
        // given
        write('order/application/port/in/command/CreateOrderCommand.java', 'public record CreateOrderCommand(String name) {}')
        write('order/application/port/in/usecase/CreateOrderUseCase.java', '@FunctionalInterface public interface CreateOrderUseCase { void execute(com.example.order.application.port.in.command.CreateOrderCommand command); }')
        write('order/adapter/in/web/request/CreateOrderRequest.java', 'public record CreateOrderRequest(String name) { public com.example.order.application.port.in.command.CreateOrderCommand toCommand() { return new com.example.order.application.port.in.command.CreateOrderCommand(name); } }')
        String controller = '''
            import com.example.order.application.port.in.command.CreateOrderCommand;
            import com.example.order.application.port.in.usecase.CreateOrderUseCase;
            import com.example.order.adapter.in.web.request.CreateOrderRequest;
            public final class OrderController {
                private final CreateOrderUseCase useCase = null;
                public void create(final CreateOrderRequest request) {
                    final CreateOrderCommand command = request.toCommand();
                    useCase.execute(command);
                }
            }
        '''
        write('order/adapter/in/web/OrderController.java', controller)
        // when & then
        assert web().empty
        write('order/adapter/in/web/OrderController.java', controller.replace('final CreateOrderCommand command = request.toCommand();', 'request.toCommand(); final CreateOrderCommand command = new CreateOrderCommand(request.name());'))
        assert web().any { it.contains('controller must pass Request conversion') }
        write('order/adapter/in/web/CreateOrderMapper.java', '''
            import com.example.order.adapter.in.web.request.CreateOrderRequest;
            import com.example.order.application.port.in.command.CreateOrderCommand;
            public final class CreateOrderMapper { public static CreateOrderCommand toCommand(final CreateOrderRequest request) { return new CreateOrderCommand(request.name()); } }
        ''')
        write('order/adapter/in/web/OrderController.java', controller.replace('request.toCommand()', 'CreateOrderMapper.toCommand(request)'))
        assert web().empty
    }

    @Test
    @DisplayName('DTO와 계약의 이름이 달라도 컨트롤러가 사용하는 실제 계약을 기준으로 변환을 요구한다')
    void discovers_contracts_from_controller_calls() {
        // given
        write('order/application/port/in/command/PlaceOrderCommand.java', 'public record PlaceOrderCommand(String name) {}')
        write('order/application/port/in/result/OrderSummary.java', 'public record OrderSummary(String name) {}')
        write('order/application/port/in/usecase/PlaceOrderUseCase.java', '''
            import com.example.order.application.port.in.command.PlaceOrderCommand;
            import com.example.order.application.port.in.result.OrderSummary;
            @FunctionalInterface public interface PlaceOrderUseCase { OrderSummary execute(PlaceOrderCommand command); }
        ''')
        write('order/adapter/in/web/request/CheckoutRequest.java', 'public record CheckoutRequest(String name) {}')
        write('order/adapter/in/web/response/ReceiptResponse.java', 'public record ReceiptResponse(String name) {}')
        write('order/adapter/in/web/OrderController.java', '''
            import com.example.order.adapter.in.web.request.CheckoutRequest;
            import com.example.order.adapter.in.web.response.ReceiptResponse;
            import com.example.order.application.port.in.usecase.PlaceOrderUseCase;
            public class OrderController {
                private final PlaceOrderUseCase useCase = null;
                public ReceiptResponse create(final CheckoutRequest request) { useCase.execute(null); return null; }
            }
        ''')
        // when
        def violations = web()
        // then
        assert violations.any { it.contains("'CheckoutRequest'") && it.contains('must provide toCommand') }
        assert violations.any { it.contains("'ReceiptResponse'") && it.contains('must provide from') }
    }

    @Test
    @DisplayName('서로 다른 패키지의 제네릭 상속과 JDK Function 및 복합 타입 인자를 정확히 센다')
    void resolves_generic_interface_substitution_across_packages() {
        // given
        write('global/domain/Parent.java', '@FunctionalInterface public interface Parent<T> { T convert(T value); }')
        write('global/messaging/Child.java', '''
            import com.example.global.domain.Parent;
            public interface Child<U extends Number> extends Parent<U> { U convert(U value); }
        ''')
        // when & then
        assert declarations().any { it.contains("interface 'Child'") }
        write('global/messaging/Child.java', '@FunctionalInterface public interface Child extends java.util.function.Function<String, Integer> { Integer apply(String value); }')
        assert declarations().empty
        write('global/messaging/Child.java', '''
            import com.example.global.domain.Parent;
            @FunctionalInterface public interface Child extends Parent<java.util.Map<String,Integer>> {
                java.util.Map<String,Integer> convert(java.util.Map<String,Integer> value);
            }
        ''')
        assert declarations().empty
    }

    private List<String> declarations() { DeclarationConventionValidator.validate(project(), index()) }
    private List<String> messaging() {
        BoundedContexts.withRoots(['com.example'] as Set) { MessagingConventionValidator.validate(project(), index()) }
    }
    private List<String> web() { WebConversionFlowValidator.validate(project(), index()) }
    private def project() { ProjectBuilder.builder().withProjectDir(directory).build() }
    private SourceTypeIndex index() {
        List<File> files = []
        new File(directory, 'src/main/java').eachFileRecurse { if (it.name.endsWith('.java')) files.add(it) }
        new SourceTypeIndex(JavaSourceAstInspector.inspectAll(files).values())
    }
    private File file(String path) { new File(directory, 'src/main/java/com/example/' + path) }
    private void write(String path, String source) {
        File target = file(path)
        target.parentFile.mkdirs()
        String pkg = path.contains('/') ? 'com.example.' + path.substring(0, path.lastIndexOf('/')).replace('/', '.') : 'com.example'
        target.text = 'package ' + pkg + ';\n' + source.stripIndent()
    }
}
