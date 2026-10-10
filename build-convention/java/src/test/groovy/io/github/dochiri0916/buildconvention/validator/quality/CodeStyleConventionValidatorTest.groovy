package io.github.dochiri0916.buildconvention.validator.quality

import io.github.dochiri0916.buildconvention.source.java.JavaSourceAstInspector
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CodeStyleConventionValidatorTest {
    @TempDir
    File tempDir

    @Test
    @DisplayName('일반 클래스와 모든 명시적 파라미터는 final이어야 한다')
    void requires_final_classes_and_parameters() {
        // given
        String valid = 'public final class Example { private Example(final String value) {} public void run(final String value) {} }'

        // when
        List<String> violations = validate(valid.replace('public final class', 'public class').replace('final String', 'String'))

        // then
        assert validate(valid).isEmpty()
        assert violations.any { it.contains('class') && it.contains('must be final') }
        assert violations.count { it.contains('every parameter final') } == 2
        assert validate(valid.replace('final String', '/* final */ String')).count { it.contains('every parameter final') } == 2
    }

    @Test
    @DisplayName('상속 기반 클래스와 프록시 및 JPA Entity는 final 클래스 규칙에서 제외한다')
    void permits_declared_framework_exceptions() {
        // given
        String base = 'public abstract class Example {}'

        // when
        List<String> violations = validate('@Transactional public class Example {}', 'application.service')

        // then
        assert validate(base).isEmpty()
        assert violations.isEmpty()
        assert validate('@Entity public class Example {}', 'adapter.out.persistence').isEmpty()
        assert validate('@Transactional public class Example {}', 'domain.model').any { it.contains('must be final') }
        assert validate('public enum Example { OPEN }').isEmpty()
        assert validate('public record Example(String value) { public Example { if (value == null) throw new InvalidExampleException(); } }').isEmpty()
    }

    @Test
    @DisplayName('else와 중첩 분기를 거부하고 early return은 허용한다')
    void rejects_else_and_deep_nesting() {
        // given
        String valid = 'public final class Example { public void run(final boolean ready) { if (!ready) { return; } execute(); } }'
        String nested = valid.replace('return;', 'if (ready) { return; }')
        String alternate = valid.replace('} execute();', '} else { execute(); }')

        // when
        List<String> violations = validate(nested)

        // then
        assert validate(valid).isEmpty()
        assert violations.any { it.contains('one level') }
        assert validate(alternate).any { it.contains('instead of else') }
        assert validate('public final class Example { public void run() { String hint = "else if if"; } }').isEmpty()
    }

    @Test
    @DisplayName('본문이 없는 인터페이스·추상 메서드의 파라미터는 final을 요구하지 않는다')
    void exempts_parameters_of_bodyless_methods_from_final_rule() {
        // given
        String abstractMethods = 'public interface Example { void run(String value); }'
        String concrete = 'public final class Example { public void run(String value) {} }'

        // when
        def abstractViolations = validate(abstractMethods)
        def concreteViolations = validate(concrete)

        // then
        assert !abstractViolations.any { it.contains('must declare every parameter final') }
        assert concreteViolations.any { it.contains('must declare every parameter final') }
    }

    @Test
    @DisplayName('필드 접근자는 get을 쓰지 않고 setter 이름도 금지한다')
    void validates_accessors() {
        // given
        String source = 'public final class Example { private final String value = null; public String getValue() { return value; } public void setValue(final String value) {} }'

        // when
        List<String> violations = validate(source)

        // then
        assert violations.any { it.contains('without get') }
        assert violations.any { it.contains('setter naming') }
        assert validate(source.replace('getValue', 'value').replace('setValue', 'change')).isEmpty()
        assert validate('public final class Example { public void getOrder() {} }', 'application.service').isEmpty()
    }

    @Test
    @DisplayName('일반 호출 체이닝은 거부하고 Stream Optional builder 예외는 허용한다')
    void validates_dot_rule_and_exceptions() {
        // given
        String prefix = 'public final class Example { public void run() {'

        // when
        List<String> violations = validate(prefix + ' order.customer().name(); } }')

        // then
        assert violations.any { it.contains('one dot per line') }
        assert validate(prefix + ' values.stream().map(value -> value).toList(); } }').isEmpty()
        assert validate(prefix + ' Optional.ofNullable(value).orElse(null); } }').isEmpty()
        assert validate(prefix + ' Order.builder().name(value).build(); } }').isEmpty()
        assert validate(prefix + ' /* order.customer().name(); */ String hint = "order.customer().name()"; } }').isEmpty()
        assert validate(prefix + ' final double value = 1.25; new java.util.ArrayList<String>(); } }').isEmpty()
        assert validate(prefix + ' values.stream().toList(); order.customer().name(); } }')
                .any { it.contains('one dot per line') }
    }

    @Test
    @DisplayName('Entity와 Adapter DTO의 프레임워크 스타일 예외에도 final 파라미터는 적용한다')
    void preserves_parameter_rule_for_framework_types() {
        // given
        String source = '@Entity public class Example { public void setValue(String value) { if (value == null) { if (ready()) return; } else run(); } }'

        // when
        List<String> violations = validate(source, 'adapter.out.persistence')

        // then
        assert violations.size() == 1
        assert violations.first().contains('every parameter final')
        assert validate('public record Example(String value) { public String result() { return data.value().name(); } }', 'adapter.in.web.response').isEmpty()
        assert validate('public class Example {}', 'configuration').isEmpty()
        assert validate('public final class Example { public class Nested {} }').any { it.contains("'Nested'") }
    }

    private List<String> validate(String source, String child = 'domain.model') {
        def project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        File file = new File(tempDir, 'Example.java')
        file.text = "package com.example.order.${child};\n${source}"
        def inspection = JavaSourceAstInspector.inspect(file)
        assert inspection.valid
        CodeStyleConventionValidator.validate(project, file, inspection.packageName, inspection.primaryType())
    }
}
