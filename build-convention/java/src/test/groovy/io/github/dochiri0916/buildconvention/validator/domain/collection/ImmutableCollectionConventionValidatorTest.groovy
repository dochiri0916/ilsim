package io.github.dochiri0916.buildconvention.validator.domain.collection

import io.github.dochiri0916.buildconvention.source.java.JavaSourceAstInspector
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ImmutableCollectionConventionValidatorTest {
    @TempDir
    File tempDir

    @Test
    @DisplayName('Set과 Map의 복사를 허용하며 생성자마다 복사 누락을 거부한다')
    void requires_copy_in_every_constructor() {
        // given
        String valid = 'public record Values(java.util.Set<String> values) { public Values { values = java.util.Set.copyOf(values); } }'
        String overloaded = '''
            public final class Values {
                private final java.util.Map<String, String> values;
                private Values(final java.util.Map<String, String> values) { this.values = java.util.Map.copyOf(values); }
                private Values(final java.util.Map<String, String> values, final boolean copy) { this.values = values; }
            }
        '''

        // when
        List<String> violations = validate(overloaded)

        // then
        assert validate(valid).isEmpty()
        assert validate(valid.replace('Set', 'Map').replace('Map<String>', 'Map<String, String>')).isEmpty()
        assert violations.any { it.contains('every constructor') }
    }

    @Test
    @DisplayName('mutable class와 공개 필드 및 내부 컬렉션 변경을 거부한다')
    void rejects_mutability() {
        // given
        String valid = '''
            public final class Values {
                private final java.util.List<String> values;
                private Values(final java.util.List<String> values) { this.values = java.util.List.copyOf(values); }
                public java.util.List<String> values() { return values; }
            }
        '''

        // when
        List<String> violations = validate(valid.replace('return values;', 'values.addAll(other); return values;'))

        // then
        assert validate(valid).isEmpty()
        assert validate(valid.replace('public final class', 'public class')).any { it.contains('immutable final class') }
        assert validate(valid.replace('private final java.util.List', 'public java.util.List')).any { it.contains('private final state') }
        assert violations.any { it.contains('must not mutate') }
        assert validate(valid.replace('java.util.List.copyOf(values)', 'values /* List.copyOf(values) */'))
                .any { it.contains('defensively copy') }
    }

    @Test
    @DisplayName('List 별칭 대신 Collection과 배열을 써도 검사를 우회하지 못한다')
    void rejects_aliases_arrays_and_empty_constructors() {
        // given
        String source = 'public record Values(java.util.Collection<String> values) {}'

        // when
        List<String> violations = validate(source)

        // then
        assert violations.any { it.contains('defensively copy') }
        assert validate(source.replace('java.util.Collection<String>', 'String[]')).any { it.contains('defensively copy') }
        assert validate('public final class Values { private final java.util.List<String> values = null; }')
                .any { it.contains('defensively copy') }
        assert validate('public record Values(String value) {}').isEmpty()
    }

    private List<String> validate(String source) {
        File file = new File(tempDir, 'Values.java')
        file.text = source
        def inspection = JavaSourceAstInspector.inspect(file)
        assert inspection.valid
        ImmutableCollectionConventionValidator.validate(file.name, inspection.primaryType(), false)
    }
}
