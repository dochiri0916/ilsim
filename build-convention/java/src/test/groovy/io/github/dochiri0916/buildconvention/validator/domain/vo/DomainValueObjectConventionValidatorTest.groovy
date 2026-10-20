package io.github.dochiri0916.buildconvention.validator.domain.vo

import io.github.dochiri0916.buildconvention.source.java.JavaSourceAstInspector
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DomainValueObjectConventionValidatorTest {
    @TempDir
    File tempDir

    @Test
    @DisplayName('private 생성자와 값 동등성을 갖춘 VO와 UUID v7 식별자를 허용한다')
    void acceptsValueObjectAndIdentifier() {
        String source = identifier()

        List<String> violations = validate('OrderId', source)

        assert violations.isEmpty()
        assert validate('MemberReferenceId', source.replace('OrderId', 'MemberReferenceId')
                .replace('public static MemberReferenceId generate() { return of(com.github.f4b6a3.uuid.UuidCreator.getTimeOrderedEpoch()); }', '')).isEmpty()
    }

    @Test
    @DisplayName('record와 공개 및 package-private VO 생성자를 거부한다')
    void rejectsExposedConstructorsAndRecords() {
        String source = identifier()

        List<String> violations = validate('OrderId', source.replace('private OrderId', 'OrderId'))

        assert violations.any { it.contains('only private constructors') }
        assert validate('OrderId', 'public record OrderId(java.util.UUID value) {}')
                .any { it.contains('final class with a private constructor') }
        assert validate('OrderId', source.replace('private OrderId', 'public OrderId'))
                .any { it.contains('only private constructors') }
    }

    @Test
    @DisplayName('VO 불변성과 of 팩터리 및 구성 값 동등성 누락을 거부한다')
    void rejectsMutableValueAndMissingContract() {
        String source = identifier().replace('private final UUID value', 'public UUID value')
                .replace('public final class', 'public class').replace('static OrderId of', 'static OrderId from')
                .replace('value.equals(other.value)', 'true').replace('value.hashCode()', '1')

        List<String> violations = validate('OrderId', source)

        assert violations.any { it.contains('private final fields') }
        assert violations.any { it.contains('must be final') }
        assert violations.any { it.contains('of factory') }
        assert violations.any { it.contains('factories must use') }
        assert violations.count { it.contains('all component values') } == 2
    }

    @Test
    @DisplayName('UUID v4와 v7 검증 누락 및 주석과 문자열을 통한 우회를 거부한다')
    void rejectsIdentifierBypass() {
        String source = identifier().replace('if (value.version() != 7) throw new InvalidOrderIdException();',
                '/* if (value.version() != 7) throw new InvalidOrderIdException(); */ String hint = "value.version() != 7 throw";')
                .replace('return of(com.github.f4b6a3.uuid.UuidCreator.getTimeOrderedEpoch());', 'return of(UUID.randomUUID());')

        List<String> violations = validate('OrderId', source)

        assert violations.any { it.contains('reject non-v7') }
        assert violations.any { it.contains('instead of randomUUID') }
        assert validate('OrderId', identifier().replace('public static OrderId generate() { return of(com.github.f4b6a3.uuid.UuidCreator.getTimeOrderedEpoch()); }', ''))
                .any { it.contains('generate factory') }
        assert validate('OrderId', identifier().replace('UUID value', 'String value'))
                .any { it.contains('wrap a UUID v7') }
    }

    @Test
    @DisplayName('일급 컬렉션은 방어 복사하고 컬렉션 하나만 가진다')
    void validatesFirstClassCollection() {
        String source = '''
            public final class OrderLines {
                private final java.util.List<OrderLine> values;
                private OrderLines(java.util.List<OrderLine> values) {
                    if (values == null) throw new InvalidOrderLinesException();
                    this.values = java.util.List.copyOf(values);
                }
                public static OrderLines of(java.util.List<OrderLine> values) { return new OrderLines(values); }
                public java.util.List<OrderLine> values() { return values; }
                public boolean equals(Object object) { return object instanceof OrderLines other && values.equals(other.values); }
                public int hashCode() { return values.hashCode(); }
            }
        '''

        List<String> violations = validate('OrderLines', source)

        assert violations.isEmpty()
        assert validate('OrderLines', source.replace('java.util.List.copyOf(values)', 'values'))
                .any { it.contains('defensively copy') }
        assert validate('OrderLines', source.replace('private final java.util.List', 'private final int count = 1; private final java.util.List'))
                .any { it.contains('exactly one collection') }
        assert validate('OrderLines', source.replace('return values;', 'values.clear(); return values;'))
                .any { it.contains('must not mutate') }
    }

    @Test
    @DisplayName('상태 enum 위치와 중첩 VO 및 이벤트 보관도 검사한다')
    void validatesEnumsNestedTypesAndEvents() {
        String source = 'public final class Holder { public record Inner(String value) {} }'

        List<String> violations = validate('Holder', source)

        assert violations.any { it.contains("'Inner' must be a final class") }
        assert validate('Status', 'public enum Status { OPEN }').isEmpty()
        assert !validate('Status', 'public enum Status { OPEN }', 'com.example.order.domain.model').isEmpty()
        assert validate('Order', 'public final class Order { private final Events events = null; }',
                'com.example.order.domain.model').any { it.contains('must not retain domain events') }
        assert validate('InvalidOrderException', 'public final class InvalidOrderException extends RuntimeException {}',
                'com.example.order.domain.exception').isEmpty()
        assert validate('Message', 'public record Message(String payload) {}',
                'com.example.global.domain.outbox').isEmpty()
    }

    private List<String> validate(String name, String source, String packageName = 'com.example.order.domain.vo') {
        def project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        File file = new File(tempDir, "${name}.java")
        file.text = "package ${packageName};\nimport java.util.UUID;\n${source}"
        def inspection = JavaSourceAstInspector.inspect(file)
        assert inspection.valid
        DomainValueObjectConventionValidator.validate(project, file, packageName, inspection.primaryType())
    }

    private static String identifier() {
        '''
            public final class OrderId {
                private final UUID value;
                private OrderId(UUID value) {
                    if (value == null) throw new OrderIdRequiredException();
                    if (value.version() != 7) throw new InvalidOrderIdException();
                    this.value = value;
                }
                public static OrderId of(UUID value) { return new OrderId(value); }
                public static OrderId generate() { return of(com.github.f4b6a3.uuid.UuidCreator.getTimeOrderedEpoch()); }
                public boolean equals(Object object) { return object instanceof OrderId other && value.equals(other.value); }
                public int hashCode() { return value.hashCode(); }
            }
        '''
    }
}
