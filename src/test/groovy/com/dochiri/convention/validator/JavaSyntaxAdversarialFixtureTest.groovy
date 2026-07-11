package com.dochiri.convention.validator

import com.dochiri.convention.extension.HexagonalConventionExtension
import com.dochiri.convention.support.JavaSourceAstInspector
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class JavaSyntaxAdversarialFixtureTest {

    @TempDir
    File tempDir

    @Test
    @DisplayName('주석 문자열 문자와 text block의 brace 뒤에 있는 Repository 변경도 분석한다')
    void parsesTransactionalMethodBodyAfterAdversarialBraceTokens() {
        // given
        Project project = sampleProject('transaction-parser')
        writeApplication(project)
        writeAggregate(project, 'Order')
        writeAggregate(project, 'Cart')
        writeRepositoryPort(project, 'OrderRepositoryPort', 'Order')
        writeRepositoryPort(project, 'CartRepositoryPort', 'Cart')
        writeJava(project, 'src/main/java/com/example/sales/application/port/in/CheckoutUseCase.java', '''
                package com.example.sales.application.port.in;

                public interface CheckoutUseCase {
                    void checkout();
                }
                ''')
        writeJava(project, 'src/main/java/com/example/sales/application/service/CheckoutService.java', '''
                package com.example.sales.application.service;

                import com.example.sales.application.port.in.CheckoutUseCase;
                import com.example.sales.application.port.out.CartRepositoryPort;
                import com.example.sales.application.port.out.OrderRepositoryPort;
                import lombok.RequiredArgsConstructor;
                import org.springframework.stereotype.Service;
                import org.springframework.transaction.annotation.Transactional;

                @Service
                @RequiredArgsConstructor
                public final class CheckoutService implements CheckoutUseCase {
                    private final OrderRepositoryPort orderRepositoryPort;
                    private final CartRepositoryPort cartRepositoryPort;

                    @Override
                    @Transactional
                    public void checkout() {
                        String quotedBrace = "escaped quote \\" and brace }";
                        char closingBrace = '}';
                        char slash = '\\\\';
                        String json = """
                                {
                                  "nested": "}"
                                }
                                """;
                        // line comment with a fake closing brace }
                        /* block comment with fake braces { } */
                        if (!quotedBrace.isBlank()) {
                            orderRepositoryPort.save(null);
                        }
                        cartRepositoryPort.delete(null);
                    }
                }
                ''')

        // when
        List<String> violations = validate(project)

        // then
        assert violations.any {
            it.contains("application service method 'checkout' must not modify multiple aggregate repositories")
        }
    }

    @Test
    @DisplayName('Java 테스트 phase와 assertion은 text block 내부의 주석 모양에 영향받지 않는다')
    void parsesJavaTestMethodAfterAdversarialBraceAndCommentTokens() {
        // given
        Project project = sampleProject('test-parser')
        writeApplication(project)
        writeJava(project, 'src/test/java/com/example/ParserFixtureTest.java', '''
                package com.example;

                import org.junit.jupiter.api.DisplayName;
                import org.junit.jupiter.api.Test;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                class ParserFixtureTest {

                    @Test
                    @DisplayName("복잡한 문자열 뒤의 결과를 검증한다")
                    void parsesComplexBody() {
                        // given
                        String quotedBrace = "escaped quote \\" and brace }";
                        char closingBrace = '}';
                        char slash = '\\\\';
                        String json = """
                                {
                                  "commentLike": "// then }",
                                  "blockLike": "/* } */"
                                }
                                """;
                        /* block comment with fake closing brace } */
                        int expected = quotedBrace.isBlank() ? 0 : 1;

                        // when
                        int actual = json.isBlank() || closingBrace == slash ? 0 : 1;

                        // then
                        assertEquals(expected, actual);
                    }
                }
                ''')

        // when
        List<String> violations = validate(project)

        // then
        assert !violations.any { it.contains("test method 'parsesComplexBody'") }
    }

    @Test
    @DisplayName('줄바꿈된 fully-qualified Transactional도 비공개 메서드 위반으로 감지한다')
    void detectsMultilineQualifiedTransactionalOnPrivateMethod() {
        // given
        Project project = sampleProject('qualified-transactional')
        writeApplication(project)
        writeJava(project, 'src/main/java/com/example/sales/application/service/RegisterOrderService.java', '''
                package com.example.sales.application.service;

                import org.springframework.stereotype.Service;
                import org.springframework.transaction.annotation.Transactional;

                @Service
                public final class RegisterOrderService {

                    @Transactional
                    public void register() {
                    }

                    @org.springframework.transaction.annotation
                        .Transactional
                    private void persist() {
                    }
                }
                ''')
        File sourceFile = new File(
                project.projectDir,
                'src/main/java/com/example/sales/application/service/RegisterOrderService.java'
        )
        JavaSourceAstInspector.Inspection inspection = JavaSourceAstInspector.inspect(sourceFile)
        JavaSourceAstInspector.MethodModel persistMethod = inspection.primaryType().methods.find { method ->
            method.name == 'persist'
        }

        // when
        List<String> violations = validate(project)

        // then
        assert persistMethod.annotations*.simpleName.contains('Transactional')
        assert violations.any {
            it.contains("non-public method 'persist' must not declare @Transactional")
        }
    }

    @Test
    @DisplayName('annotation 문자열의 닫는 괄호 뒤 readOnly 속성도 AST로 인식한다')
    void detectsReadOnlyAfterClosingParenthesisInsideAnnotationString() {
        // given
        Project project = sampleProject('nested-transaction-argument')
        writeApplication(project)
        writeJava(project, 'src/main/java/com/example/sales/application/port/out/OrderRepositoryPort.java', '''
                package com.example.sales.application.port.out;

                public interface OrderRepositoryPort {
                    void save();
                }
                ''')
        writeJava(project, 'src/main/java/com/example/sales/application/service/GetOrderService.java', '''
                package com.example.sales.application.service;

                import com.example.sales.application.port.out.OrderRepositoryPort;
                import lombok.RequiredArgsConstructor;
                import org.springframework.stereotype.Service;
                import org.springframework.transaction.annotation.Transactional;

                @Service
                @RequiredArgsConstructor
                public final class GetOrderService {

                    private final OrderRepositoryPort orderRepositoryPort;

                    @Transactional(
                        timeoutString = "#{T(java.lang.Math).max(1, 2)}",
                        readOnly = true
                    )
                    public void getOrder() {
                        orderRepositoryPort.save();
                    }
                }
                ''')

        // when
        List<String> violations = validate(project)

        // then
        assert !violations.any {
            it.contains("query application service method 'getOrder' must declare @Transactional(readOnly = true)")
        }
        assert violations.any {
            it.contains("read-only transaction method 'getOrder' must not call repository mutation methods")
        }
    }

    @Test
    @DisplayName('annotation 문자열의 괄호 뒤 테스트 메서드도 품질 규칙을 검증한다')
    void validatesTestMethodAfterClosingParenthesisInsideAnnotationString() {
        // given
        Project project = sampleProject('nested-test-annotation-argument')
        writeApplication(project)
        writeJava(project, 'src/test/java/com/example/ParserFixtureTest.java', '''
                package com.example;

                import org.junit.jupiter.api.DisplayName;
                import org.junit.jupiter.api.Test;

                class ParserFixtureTest {

                    @Test
                    @DisplayName("구조가 없는 테스트를 거부한다")
                    @Example(label = "closing ) token")
                    void missingStructure() {
                        int value = 1;
                    }
                }
                ''')

        // when
        List<String> violations = validate(project)

        // then
        assert violations.any { it.contains("test method 'missingStructure' must include '// given'") }
        assert violations.any { it.contains("test method 'missingStructure' must assert observable result") }
    }

    @Test
    @DisplayName('줄바꿈된 fully-qualified Disabled도 AST로 거부한다')
    void detectsMultilineQualifiedDisabledAnnotation() {
        // given
        Project project = sampleProject('qualified-disabled')
        writeApplication(project)
        writeJava(project, 'src/test/java/com/example/DisabledFixtureTest.java', '''
                package com.example;

                @org.junit.jupiter.api
                    .Disabled
                class DisabledFixtureTest {
                }
                ''')

        // when
        List<String> violations = validate(project)

        // then
        assert violations.any { it.contains('tests must not use @Disabled') }
    }

    @Test
    @DisplayName('메서드 호출 체인 안의 중첩 타입 생성을 fully-qualified 타입 참조로 오인하지 않는다')
    void ignoresNestedTypeConstructionInsideMethodCallChain() {
        // given
        Project project = sampleProject('member-call-expression')
        writeJava(project, 'src/main/java/com/example/ExpressionFixture.java', '''
                package com.example;

                public final class ExpressionFixture {

                    public Object map(final Object source) {
                        return source.toString()
                                .lines()
                                .map(value -> new Result.Item(value))
                                .toList();
                    }

                    private record Result() {
                        private record Item(String value) {
                        }
                    }
                }
                ''')

        // when
        JavaSourceAstInspector.Inspection inspection = JavaSourceAstInspector.inspect(
                new File(project.projectDir, 'src/main/java/com/example/ExpressionFixture.java')
        )

        // then
        assert inspection.primaryType().qualifiedTypeReferences.isEmpty()
    }

    private Project sampleProject(String name) {
        File projectDir = new File(tempDir, name)
        projectDir.mkdirs()
        return ProjectBuilder.builder().withProjectDir(projectDir).build()
    }

    private static List<String> validate(Project project) {
        return JavaSourceArchitectureValidator.validate(project, new HexagonalConventionExtension())
    }

    private static void writeApplication(Project project) {
        writeJava(project, 'src/main/java/com/example/TestApplication.java', '''
                package com.example;
                public class TestApplication {
                }
                ''')
    }

    private static void writeAggregate(Project project, String typeName) {
        writeJava(project, "src/main/java/com/example/sales/domain/model/${typeName}.java", """
                package com.example.sales.domain.model;

                public record ${typeName}(${typeName}Id id) {
                    public ${typeName} {
                        if (id == null) {
                            throw new IllegalArgumentException();
                        }
                    }
                    public static ${typeName} create(final ${typeName}Id id) {
                        return new ${typeName}(id);
                    }
                    @Override
                    public boolean equals(final Object other) {
                        return this == other || other instanceof ${typeName} target && id.equals(target.id);
                    }
                    @Override
                    public int hashCode() {
                        return id.hashCode();
                    }
                }
                """)
        writeJava(project, "src/main/java/com/example/sales/domain/model/${typeName}Id.java", """
                package com.example.sales.domain.model;

                public record ${typeName}Id(String value) {
                    public ${typeName}Id {
                        if (value == null) {
                            throw new IllegalArgumentException();
                        }
                        if (value.isBlank()) {
                            throw new IllegalArgumentException();
                        }
                    }
                    public static ${typeName}Id generate() {
                        return new ${typeName}Id("${typeName.toLowerCase()}-id");
                    }
                }
                """)
    }

    private static void writeRepositoryPort(Project project, String portName, String aggregateName) {
        writeJava(project, "src/main/java/com/example/sales/application/port/out/${portName}.java", """
                package com.example.sales.application.port.out;

                import com.example.sales.domain.model.${aggregateName};

                public interface ${portName} {
                    void save(${aggregateName} aggregate);
                    void delete(${aggregateName} aggregate);
                }
                """)
    }

    private static void writeJava(Project project, String path, String source) {
        File file = new File(project.projectDir, path)
        file.parentFile.mkdirs()
        file.text = source.stripIndent().trim() + System.lineSeparator()
    }
}
