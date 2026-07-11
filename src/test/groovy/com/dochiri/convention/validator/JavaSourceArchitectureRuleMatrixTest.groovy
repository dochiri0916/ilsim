package com.dochiri.convention.validator

import com.dochiri.convention.extension.HexagonalConventionExtension
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class JavaSourceArchitectureRuleMatrixTest {

    @TempDir
    File tempDir

    @Test
    @DisplayName('Application 타입의 패키지와 Port Service 계약 위반을 모두 거부한다')
    void rejectsApplicationPackageAndContractViolations() {
        // given
        Project project = sampleProject('application-rules')
        writeApplication(project)
        writeJava(project, 'com/example/order/application/service/CreateOrderUseCase.java', '''
                package com.example.order.application.service;

                public class CreateOrderUseCase {
                }
                ''')
        writeJava(project, 'com/example/order/application/service/CreateOrderCommand.java', '''
                package com.example.order.application.service;

                public class CreateOrderCommand {
                }
                ''')
        writeJava(project, 'com/example/order/application/service/OrderRepositoryPort.java', '''
                package com.example.order.application.service;

                public interface OrderRepositoryPort {
                }
                ''')
        writeJava(project, 'com/example/order/application/port/in/LegacyOrderUseCase.java', '''
                package com.example.order.application.port.in;

                public class LegacyOrderUseCase {
                }
                ''')
        writeJava(project, 'com/example/order/application/port/in/CreateLegacyOrderCommand.java', '''
                package com.example.order.application.port.in;

                public class CreateLegacyOrderCommand {
                }
                ''')
        writeJava(project, 'com/example/order/application/port/out/OrderLookup.java', '''
                package com.example.order.application.port.out;

                public interface OrderLookup {
                }
                ''')
        writeJava(project, 'com/example/order/application/port/out/dto/OrderReadModel.java', '''
                package com.example.order.application.port.out.dto;

                public class OrderReadModel {
                }
                ''')
        writeJava(project, 'com/example/order/application/service/BrokenService.java', '''
                package com.example.order.application.service;

                public class BrokenService {
                    public void execute() {
                    }
                }
                ''')
        writeJava(project, 'com/example/order/application/exception/BrokenFlowException.java', '''
                package com.example.order.application.exception;

                public final class BrokenFlowException extends Exception {
                    public BrokenFlowException() {
                    }
                }
                ''')
        writeJava(project, 'com/example/order/application/exception/BrokenFlowErrorCode.java', '''
                package com.example.order.application.exception;

                public final class BrokenFlowErrorCode {
                }
                ''')
        writeJava(project, 'com/example/order/application/exception/FailureReason.java', '''
                package com.example.order.application.exception;

                public enum FailureReason {
                    FAILURE
                }
                ''')
        writeJava(project, 'com/example/order/application/service/AdapterAwareService.java', '''
                package com.example.order.application.service;

                import com.example.global.error.ApiProblemDetailFactory;
                import com.example.order.adapter.out.persistence.OrderEntity;

                public final class AdapterAwareService {
                    private ApiProblemDetailFactory problemFactory;
                    private OrderEntity entity;
                }
                ''')

        // when
        List<String> violations = validate(project)

        // then
        assertContains(violations, "inbound port 'CreateOrderUseCase' must live in application..port.in")
        assertContains(violations, "application DTO 'CreateOrderCommand' must live in application..port.in")
        assertContains(violations, "outbound port 'OrderRepositoryPort' must live in application..port.out")
        assertContains(violations, "inbound port 'LegacyOrderUseCase' must be an interface")
        assertContains(violations, "application DTO 'CreateLegacyOrderCommand' must be a record")
        assertContains(violations, "outbound port 'OrderLookup' must end with Port or RepositoryPort")
        assertContains(violations, "outbound DTO 'OrderReadModel' must be a record")
        assertContains(violations, "application service 'BrokenService' must be final")
        assertContains(violations, "application service 'BrokenService' must implement an inbound UseCase")
        assertContains(violations, "application exception 'BrokenFlowException' must extend RuntimeException")
        assertContains(violations, "application exception 'BrokenFlowException' must declare serialVersionUID")
        assertContains(violations, "application error code 'BrokenFlowErrorCode' must be an enum")
        assertContains(violations, "application exception support 'FailureReason' must end with Exception or ErrorCode")
        assertContains(violations, 'application must not depend on adapter/global error layer')
    }

    @Test
    @DisplayName('Web Adapter와 오류 변환 책임을 벗어난 타입 사용을 거부한다')
    void rejectsWebAdapterAndErrorMappingViolations() {
        // given
        Project project = sampleProject('web-rules')
        writeApplication(project)
        writeJava(project, 'com/example/order/application/service/MisplacedMapper.java', '''
                package com.example.order.application.service;

                public final class MisplacedMapper implements ApiExceptionMapper {
                }
                ''')
        writeJava(project, 'com/example/order/application/service/LegacyRequest.java', '''
                package com.example.order.application.service;

                public final class LegacyRequest {
                }
                ''')
        writeJava(project, 'com/example/order/adapter/in/web/OrderRequest.java', '''
                package com.example.order.adapter.in.web;

                public final class OrderRequest {
                }
                ''')
        writeJava(project, 'com/example/order/adapter/in/web/request/ApiRequest.java', '''
                package com.example.order.adapter.in.web.request;

                public final class ApiRequest {
                }
                ''')
        writeJava(project, 'com/example/order/adapter/in/web/response/OrderResponse.java', '''
                package com.example.order.adapter.in.web.response;

                import com.example.order.domain.model.Order;

                public record OrderResponse(Order order) {
                }
                ''')
        writeJava(project, 'com/example/order/adapter/in/web/OrderController.java', '''
                package com.example.order.adapter.in.web;

                import com.example.order.adapter.out.persistence.OrderEntity;
                import com.example.order.application.service.BrokenService;
                import com.example.order.domain.model.Order;
                import org.springframework.http.ProblemDetail;

                public final class OrderController {
                    private OrderEntity entity;
                    private BrokenService service;

                    public Order load() {
                        return null;
                    }

                    public ProblemDetail fail() {
                        return ProblemDetail.forStatus(400);
                    }
                }
                ''')
        writeJava(project, 'com/example/global/error/GlobalExceptionHandler.java', '''
                package com.example.global.error;

                import com.example.order.application.exception.BrokenFlowException;
                import com.example.order.domain.exception.InvalidOrderException;
                import org.springframework.http.ProblemDetail;

                public final class GlobalExceptionHandler {
                    private BrokenFlowException applicationFailure;

                    public ProblemDetail map(final InvalidOrderException exception) {
                        ProblemDetail detail = ProblemDetail.forStatusAndDetail(400, exception.getMessage());
                        detail.setTitle("Invalid order");
                        detail.setDetail(exception.getMessage());
                        return detail;
                    }
                }
                ''')
        writeJava(project, 'com/example/order/domain/model/WebAwareOrder.java', '''
                package com.example.order.domain.model;

                import org.springframework.http.ProblemDetail;

                public record WebAwareOrder(ProblemDetail detail) {
                    public WebAwareOrder {
                        if (detail == null) {
                            throw new IllegalArgumentException();
                        }
                    }
                }
                ''')

        // when
        List<String> violations = validate(project)

        // then
        assertContains(violations, 'ApiExceptionMapper implementations must live in adapter.in.web or global.error')
        assertContains(violations, "API DTO 'LegacyRequest' must live in adapter.in.web package")
        assertContains(violations, "request DTO 'OrderRequest' must live in adapter.in.web.request package")
        assertContains(violations, "API DTO 'ApiRequest' must be a record")
        assertContains(violations, "API DTO 'ApiRequest' must be responsibility-specific")
        assertContains(violations, "API DTO 'OrderResponse' must not expose domain/application types directly")
        assertContains(violations, 'controller must depend on inbound UseCase ports only')
        assertContains(violations, 'controller must not expose domain types as response return values')
        assertContains(violations, 'controller must not create or return ProblemDetail directly')
        assertContains(violations, 'must not expose exception.getMessage() as ProblemDetail detail')
        assertContains(violations, 'must resolve user-facing ProblemDetail title/detail through code-based message catalog')
        assertContains(violations, 'GlobalExceptionHandler must delegate domain/application exception mapping')
        assertContains(violations, 'Spring Web error types are only allowed in adapter.in.web or global.error')
    }

    @Test
    @DisplayName('Domain 타입의 불변성 패키지 필드와 record 계약 위반을 거부한다')
    void rejectsDomainModelAndRecordViolations() {
        // given
        Project project = sampleProject('domain-rules')
        writeApplication(project)
        writeJava(project, 'com/example/order/domain/model/MutableOrder.java', '''
                package com.example.order.domain.model;

                import com.example.global.error.ApiErrorCode;
                import java.util.List;
                import java.util.Objects;
                import jakarta.persistence.Entity;
                import org.springframework.stereotype.Component;

                @Component
                @Entity
                public class MutableOrder {
                    private List<String> values;
                    private String name;
                    private String publicId;
                    private String memberId;
                    private Long legacyid;

                    public void validate() {
                        Objects.requireNonNull(name);
                        throw new IllegalArgumentException("bad order");
                    }
                }
                ''')
        writeJava(project, 'com/example/order/domain/model/PricingService.java', '''
                package com.example.order.domain.model;

                public class PricingService {
                }
                ''')
        writeJava(project, 'com/example/order/domain/exception/InvalidOrderException.java', '''
                package com.example.order.domain.exception;

                public final class InvalidOrderException extends Exception {
                    public InvalidOrderException() {
                    }
                }
                ''')
        writeJava(project, 'com/example/order/domain/exception/OrderErrorCode.java', '''
                package com.example.order.domain.exception;

                public final class OrderErrorCode {
                }
                ''')
        writeJava(project, 'com/example/order/domain/exception/FailureReason.java', '''
                package com.example.order.domain.exception;

                public enum FailureReason {
                    FAILURE
                }
                ''')
        writeJava(project, 'com/example/order/domain/event/OrderPlaced.java', '''
                package com.example.order.domain.event;

                public final class OrderPlaced {
                }
                ''')
        writeJava(project, 'com/example/order/domain/model/OrderCreatedEvent.java', '''
                package com.example.order.domain.model;

                public record OrderCreatedEvent() {
                }
                ''')
        writeJava(project, 'com/example/order/domain/model/BadAggregate.java', '''
                package com.example.order.domain.model;

                import java.util.List;

                public record BadAggregate(String name, String publicId, String memberId, List<String> values) {
                }
                ''')
        writeJava(project, 'com/example/order/domain/model/StringList.java', '''
                package com.example.order.domain.model;

                import java.util.List;

                public record StringList(List<String> values) {
                    public StringList {
                        if (values == null) {
                            throw new IllegalArgumentException();
                        }
                    }
                }
                ''')
        writeJava(project, 'com/example/order/domain/model/CustomerId.java', '''
                package com.example.order.domain.model;

                public record CustomerId(String value) {
                    public CustomerId {
                        if (value == null) {
                            throw new IllegalArgumentException();
                        }
                        if (value.isBlank()) {
                            throw new IllegalArgumentException();
                        }
                    }
                }
                ''')
        writeJava(project, 'com/example/order/domain/model/Order.java', '''
                package com.example.order.domain.model;

                public record Order(OrderId id) {
                    public Order {
                        if (id == null) {
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

        // when
        List<String> violations = validate(project)

        // then
        assertContains(violations, "domain class 'MutableOrder' must be a record or final aggregate root")
        assertContains(violations, 'domain must not use Spring/JPA/QueryDSL/Lombok annotations')
        assertContains(violations, 'domain invariants must not use requireNonNull')
        assertContains(violations, 'domain must use domain-specific exceptions instead of JDK basic exceptions')
        assertContains(violations, 'domain must not depend on global.error')
        assertContains(violations, 'domain must not declare DB technical id fields')
        assertContains(violations, "domain field 'values' must use a first-class collection")
        assertContains(violations, "domain field 'name' must use a Value Object")
        assertContains(violations, "domain reference field 'publicId' must use '{Target}Id' naming")
        assertContains(violations, "domain reference field 'memberId' must use an identifier Value Object type")
        assertContains(violations, "domain service 'PricingService' must be final")
        assertContains(violations, "domain exception 'InvalidOrderException' must extend RuntimeException")
        assertContains(violations, "domain exception 'InvalidOrderException' must declare serialVersionUID")
        assertContains(violations, "domain error code 'OrderErrorCode' must be an enum")
        assertContains(violations, "domain exception support 'FailureReason' must end with Exception or ErrorCode")
        assertContains(violations, "domain event 'OrderPlaced' must be a record")
        assertContains(violations, "domain event 'OrderCreatedEvent' must live in domain.event package")
        assertContains(violations, "domain record component 'values' must be wrapped in a first-class collection")
        assertContains(violations, "domain record component 'name' must use a Value Object")
        assertContains(violations, "domain reference component 'publicId' must use '{Target}Id' naming")
        assertContains(violations, "domain reference component 'memberId' must use an identifier Value Object type")
        assertContains(violations, "domain record 'BadAggregate' must declare a compact constructor")
        assertContains(violations, "first-class collection record 'StringList' must use a domain-specific name")
        assertContains(violations, "first-class collection record 'StringList' must defensively copy its collection")
        assertContains(violations, "first-class collection record 'StringList' must reject null elements")
        assertContains(violations, "identifier VO 'CustomerId' must expose a generate factory")
        assertContains(violations, 'domain entity record with identifier VO must override equals and hashCode using id')
        assertContains(violations, "domain entity record with identifier VO must expose a static factory returning 'Order'")
    }

    @Test
    @DisplayName('JPA Entity Persistence Adapter와 Mapper의 기술 책임 위반을 거부한다')
    void rejectsPersistenceAndMapperViolations() {
        // given
        Project project = sampleProject('persistence-rules')
        writeApplication(project)
        writeJava(project, 'com/example/order/adapter/out/misc/OrderJpaRepository.java', '''
                package com.example.order.adapter.out.misc;

                public final class OrderJpaRepository {
                }
                ''')
        writeJava(project, 'com/example/order/adapter/out/misc/OrderEntity.java', '''
                package com.example.order.adapter.out.misc;

                import jakarta.persistence.Entity;
                import jakarta.persistence.Id;
                import jakarta.persistence.ManyToOne;
                import lombok.Builder;
                import lombok.Data;
                import lombok.Setter;

                @Entity
                @Setter
                @Data
                @Builder
                public final class OrderEntity {
                    @Id
                    private Long technicalKey;
                    public String value;
                    private CustomerEntity customer;
                    private String publicId;
                    private Long memberId;

                    @ManyToOne
                    private ProductEntity product;

                    public OrderEntity(final String value) {
                        this.value = value;
                    }
                }
                ''')
        writeJava(project, 'com/example/order/adapter/out/persistence/ProductEntity.java', '''
                package com.example.order.adapter.out.persistence;

                import jakarta.persistence.Column;
                import jakarta.persistence.Entity;
                import jakarta.persistence.Id;
                import lombok.Getter;
                import lombok.NoArgsConstructor;
                import lombok.AccessLevel;

                @Entity
                @Getter
                @NoArgsConstructor(access = AccessLevel.PROTECTED)
                public class ProductEntity {
                    @Id
                    private Long id;
                    @Column(unique = false, length = 36)
                    private String productId;
                }
                ''')
        writeJava(project, 'com/example/order/adapter/out/persistence/OrderPersistenceAdapter.java', '''
                package com.example.order.adapter.out.persistence;

                public final class OrderPersistenceAdapter {
                }
                ''')
        writeJava(project, 'com/example/order/adapter/out/persistence/OrderMapper.java', '''
                package com.example.order.adapter.out.persistence;

                import java.util.UUID;
                import org.springframework.stereotype.Component;

                @Component
                public class OrderMapper {
                    private String state;

                    public static String map(final String value) throws Exception {
                        OrderMapper.class.getDeclaredField("state").setAccessible(true);
                        return value + UUID.randomUUID();
                    }
                }
                ''')

        // when
        List<String> violations = validate(project)

        // then
        assertContains(violations, "JPA repository 'OrderJpaRepository' must be an interface")
        assertContains(violations, "JPA repository 'OrderJpaRepository' should live in a persistence/repository package")
        assertContains(violations, "outbound adapter entity 'OrderEntity' must live in a persistence package")
        assertContains(violations, "JPA entity 'OrderEntity' must live in a persistence package")
        assertContains(violations, "JPA entity 'OrderEntity' must not be final")
        assertContains(violations, "JPA entity 'OrderEntity' must declare @Getter")
        assertContains(violations, "JPA entity 'OrderEntity' must declare @NoArgsConstructor")
        assertContains(violations, "JPA entity 'OrderEntity' must not use @Setter, @Data, or @Builder")
        assertContains(violations, "JPA entity 'OrderEntity' must not declare public fields")
        assertContains(violations, "JPA entity 'OrderEntity' must not expose public constructors")
        assertContains(violations, "JPA entity 'OrderEntity' must not use object relation mappings")
        assertContains(violations, "JPA technical key must be named 'id', not 'technicalKey'")
        assertContains(violations, "JPA entity 'OrderEntity' must declare private String orderId with @Column")
        assertContains(violations, "JPA entity 'OrderEntity' must not hold entity reference field 'customer'")
        assertContains(violations, "JPA reference field 'publicId' must use '{target}Id' naming")
        assertContains(violations, "JPA reference field 'memberId' must store identifier VO value as String")
        assertContains(violations, "JPA entity 'OrderEntity' argument constructors must be private")
        assertContains(violations, "JPA entity 'OrderEntity' must expose a static factory returning 'OrderEntity'")
        assertContains(violations, "JPA technical key 'id' must declare @Getter(AccessLevel.NONE)")
        assertContains(violations, "JPA domain identifier column 'productId' must be unique")
        assertContains(violations, "JPA domain identifier column 'productId' must have length = 32")
        assertContains(violations, "persistence adapter 'OrderPersistenceAdapter' must declare @Repository")
        assertContains(violations, "persistence adapter 'OrderPersistenceAdapter' must implement an outbound Port")
        assertContains(violations, "mapper 'OrderMapper' must be final")
        assertContains(violations, "mapper 'OrderMapper' must declare a private constructor")
        assertContains(violations, "mapper 'OrderMapper' must not be registered as a Spring bean")
        assertContains(violations, "mapper 'OrderMapper' must not keep instance state")
        assertContains(violations, "mapper 'OrderMapper' method 'map' must reveal conversion direction")
        assertContains(violations, "mapper 'OrderMapper' must not generate time or UUID values")
        assertContains(violations, "mapper 'OrderMapper' must not use reflection")
    }

    private Project sampleProject(String name) {
        File projectDir = new File(tempDir, name)
        projectDir.mkdirs()
        return ProjectBuilder.builder().withProjectDir(projectDir).build()
    }

    private static List<String> validate(Project project) {
        return JavaSourceArchitectureValidator.validate(project, new HexagonalConventionExtension())
    }

    private static void assertContains(List<String> violations, String expected) {
        assert violations.any { violation -> violation.contains(expected) }:
                "Expected violation containing '${expected}', but got:\n${violations.join('\n')}"
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
