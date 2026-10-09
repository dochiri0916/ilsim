package io.github.dochiri0916.buildconvention.support

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class JavaSourceAstInspectorTest {
    @TempDir
    File tempDir

    @Test
    @DisplayName('주석으로 우회할 수 없는 final 파라미터와 분기 깊이를 AST에서 읽는다')
    void reads_parameter_modifiers_and_control_flow() {
        // given
        File file = new File(tempDir, 'Example.java')
        file.text = '''
            public final class Example {
                public void execute(final String input, /* final */ int page) {
                    if (input != null) {
                        for (int index = 0; index < page; index++) { run(); }
                    } else { run(); }
                }
                public void plain() { String hint = "if else for"; }
            }
        '''

        // when
        def inspection = JavaSourceAstInspector.inspect(file)
        def method = inspection.primaryType().methods.find { it.name == 'execute' }
        def plain = inspection.primaryType().methods.find { it.name == 'plain' }

        // then
        assert inspection.valid
        assert method.parameterNames == ['input', 'page']
        assert method.finalParameters == [true, false]
        assert method.controlDepth == 2
        assert method.hasElse
        assert plain.controlDepth == 0
        assert !plain.hasElse
    }

    @Test
    @DisplayName('sealed permits와 요청 파라미터 어노테이션을 실제 선언에서 읽는다')
    void reads_permits_and_parameter_annotations() {
        // given
        File file = new File(tempDir, 'Example.java')
        file.text = '''
            public abstract sealed class Example permits FirstFailure, example.SecondFailure {
                protected Example(final String message) {}
                public void execute(@jakarta.validation.Valid
                    @org.springframework.web.bind.annotation.RequestBody final Request body,
                    /* @jakarta.validation.Valid */ final String hint) {}
            }
        '''
        // when
        def inspection = JavaSourceAstInspector.inspect(file)
        def type = inspection.primaryType()
        def method = type.methods.find { it.name == 'execute' }
        // then
        assert inspection.valid
        assert type.permittedTypes == ['FirstFailure', 'example.SecondFailure']
        assert method.parameterAnnotations.first()*.qualifiedName == [
                'jakarta.validation.Valid', 'org.springframework.web.bind.annotation.RequestBody']
        assert method.parameterAnnotations.last().empty
    }

    @Test
    @DisplayName('모든 제어 구조를 읽고 본문 없는 인터페이스도 허용한다')
    void reads_loops_switch_try_and_bodyless_methods() {
        // given
        File file = new File(tempDir, 'Example.java')
        file.text = '''
            public interface Example {
                void execute(final String input);
                default void loops() {
                    while (ready()) { run(); }
                    do { run(); } while (ready());
                    for (String value : values) { run(); }
                    switch (state) { default: run(); }
                    try { run(); } catch (Exception exception) { run(); }
                    synchronized (this) { run(); }
                }
            }
        '''

        // when
        def inspection = JavaSourceAstInspector.inspect(file)

        // then
        assert inspection.valid
        assert inspection.primaryType().methods.find { it.name == 'execute' }.controlDepth == 0
        assert inspection.primaryType().methods.find { it.name == 'loops' }.controlDepth == 1
    }
    @Test
    @DisplayName('실제 파일 없이 전달된 스냅샷을 파싱하고 Unicode와 구문 오류도 판정한다')
    void parsesImmutableSourceSnapshots() {
        // given
        String source = 'package sample; public class Snapshot { private final String value = "한글"; }'

        // when
        def inspected = JavaSourceAstInspector.inspectSource('folder with spaces/Snapshot.java', source)

        // then
        assert inspected.valid
        assert inspected.primaryType().qualifiedName == 'sample.Snapshot'
        assert inspected.primaryType().fields.first().literalInitializer == '한글'
        assert !JavaSourceAstInspector.inspectSource('Snapshot.java', 'public class Snapshot {').valid
    }

}
