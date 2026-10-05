package io.github.dochiri0916.buildconvention.validator

import io.github.dochiri0916.buildconvention.extension.HexagonalConventionExtension
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PersistenceConventionIntegrationTest {
    @TempDir File directory

    private static final String ENTITY = '''
        package com.example.order.adapter.out.persistence;
        import jakarta.persistence.*;
        import lombok.AccessLevel;
        import lombok.Getter;
        import lombok.NoArgsConstructor;
        @Entity @Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
        @Table(name = "orders", uniqueConstraints = @UniqueConstraint(name = "uk_orders_public_id", columnNames = "public_id"))
        public class OrderEntity {
            @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Getter(AccessLevel.NONE)
            private Long id;
            @Column(name = "public_id", nullable = false, length = 36)
            private String publicId;
            @Column(name = "member_id", nullable = false, length = 36)
            private String memberId;
            private OrderEntity(final String publicId, final String memberId) {
                this.publicId = publicId;
                this.memberId = memberId;
            }
            public static OrderEntity create(final String publicId, final String memberId) {
                return new OrderEntity(publicId, memberId);
            }
        }
    '''

    @Test
    @DisplayName('자체 publicId와 부모·외부 memberId 및 자동 생성 내부 키를 허용한다')
    void accepts_public_id_and_reference_names() {
        // given
        // when
        def violations = validate(ENTITY)
        // then
        assert violations.empty
    }

    @Test
    @DisplayName('32자·nullable·기존 자체 식별자 이름·자동 PK 누락 우회를 거부한다')
    void rejects_identifier_and_key_bypasses() {
        // given
        // when
        // then
        [['length = 36', 'length = 32', 'must have length = 36'],
         ['nullable = false', 'nullable = true', 'nullable = false'],
         ['name = "public_id",', '', 'must map to public_id'],
         ['publicId', 'orderId', 'must declare private String publicId'],
         ['@GeneratedValue(strategy = GenerationType.IDENTITY)', '/* @GeneratedValue */', 'must declare @GeneratedValue']].each { variant ->
            assert validate(ENTITY.replace(variant[0], variant[1])).any { it.contains(variant[2]) }
        }
    }

    @Test
    @DisplayName('제약 이름은 상수 대신 Table에 직접 쓰며 중복 유니크 선언을 거부한다')
    void rejects_constraint_constants_and_duplicates() {
        // given
        String constant = ENTITY.replace('name = "uk_orders_public_id"', 'name = UNIQUE_NAME')
                .replace('private Long id;', 'private Long id; private static final String UNIQUE_NAME = "uk_orders_public_id";')
        // when
        def constants = validate(constant)
        def duplicates = validate(ENTITY.replace('name = "public_id", nullable', 'name = "public_id", unique = true, nullable'))
        // then
        assert constants.any { it.contains('name literals directly') }
        assert duplicates.any { it.contains('must not duplicate') }
        String misleading = ENTITY.replace('@UniqueConstraint(name = "uk_orders_public_id", columnNames = "public_id")',
                '{@UniqueConstraint(name = UNIQUE_NAME, columnNames = "public_id"), @UniqueConstraint(name = "UNIQUE_NAME", columnNames = "member_id")}')
                .replace('private Long id;', 'private Long id; private static final String UNIQUE_NAME = "uk_orders_public_id";')
        assert validate(misleading).any { it.contains('name literals directly') }
    }

    @Test
    @DisplayName('메시지·스냅샷의 목적별 UUID 키는 유지하되 원시 숫자 키로 우회하지 못한다')
    void accepts_purpose_keys_and_rejects_invalid_metadata() {
        // given
        String outbox = ENTITY.replace('OrderEntity', 'OrderOutboxEntity').replace('publicId', 'eventId')
                .replace('public_id', 'event_id')
        String snapshot = ENTITY.replace('OrderEntity', 'OrderSnapshotEntity').replace('publicId', 'aggregateId')
                .replace('public_id', 'aggregate_id')
        // when
        // then
        assert validate(outbox).empty
        assert validate(snapshot).empty
        assert validate(outbox.replace('private String eventId;', 'private long eventId;')).any {
            it.contains('message/snapshot UUID key')
        }
    }

    @Test
    @DisplayName('ElementCollection과 enum 상태 저장을 거부한다')
    void rejects_collection_mapping_and_enum_state() {
        // given
        String source = ENTITY.replace('private String memberId;', '''
            private String memberId;
            @ElementCollection private java.util.List<String> values;
            @Enumerated(EnumType.STRING) private OrderStatus status;
            private enum OrderStatus { PENDING }
        ''')
        // when
        def violations = validate(source)
        // then
        assert violations.any { it.contains('must not use object relation mappings') }
        assert violations.any { it.contains("JPA state field 'status' must store a String") }
    }

    @Test
    @DisplayName('자기 도메인의 참조도 VO를 보관하지 않으며 generic Entity 참조를 거부한다')
    void rejects_self_reference_vo_and_generic_entity_references() {
        // given
        String source = ENTITY.replace('private String memberId;', '''
            private String memberId;
            private OrderId orderId;
            @Transient private java.util.List<OrderEntity> related;
            private record OrderId(String value) {}
        ''')
        // when
        def violations = validate(source)
        // then
        assert violations.any { it.contains("JPA reference field 'orderId' must store identifier VO value as String") }
        assert violations.any { it.contains("must not hold entity reference field 'related'") }
        assert validate(ENTITY.replace('private String memberId;', 'private java.lang.String memberId;')).empty
    }

    private List<String> validate(String source) {
        def project = ProjectBuilder.builder().withProjectDir(directory).build()
        String className = (source =~ /public class ([A-Za-z][A-Za-z0-9]*)/)[0][1]
        File file = new File(directory, "src/main/java/com/example/order/adapter/out/persistence/${className}.java")
        file.parentFile.mkdirs()
        file.text = source
        return JavaSourceArchitectureValidator.validate(project, new HexagonalConventionExtension(basePackage: 'com.example'))
    }
}
