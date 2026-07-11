package com.dochiri.convention.validator

import com.dochiri.convention.extension.HexagonalConventionExtension
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class EntityNamingConventionValidatorTest {

    @TempDir
    File tempDir

    @Test
    @DisplayName('JPA Entity 타입은 Entity 접미사를 사용한다')
    void rejectsJpaEntityWithoutEntitySuffix() {
        // given
        Project project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        writeJava(project, 'com/example/order/adapter/out/persistence/OrderRecord.java', '''
                package com.example.order.adapter.out.persistence;

                import jakarta.persistence.Entity;
                import jakarta.persistence.Table;

                @Entity
                @Table(name = "orders")
                public class OrderRecord {
                }
                ''')

        // when
        List<String> violations = EntityNamingConventionValidator.validate(
                project,
                new HexagonalConventionExtension()
        )

        // then
        assert violations.any { it.contains("JPA entity 'OrderRecord' must end with Entity") }
    }

    @Test
    @DisplayName('Domain Entity와 Table annotation 누락을 함께 거부한다')
    void rejectsDomainEntityWithoutTableAnnotation() {
        // given
        Project project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        writeJava(project, 'com/example/order/domain/model/OrderEntity.java', '''
                package com.example.order.domain.model;

                import jakarta.persistence.Entity;

                @Entity
                public class OrderEntity {
                }
                ''')

        // when
        List<String> violations = EntityNamingConventionValidator.validate(
                project,
                new HexagonalConventionExtension()
        )

        // then
        assert violations.any { it.contains('uses @Entity in domain package') }
        assert violations.any { it.contains("entity 'OrderEntity' must declare @Table") }
    }

    @Test
    @DisplayName('Entity record도 AST에서 타입 이름과 Table annotation을 인식한다')
    void acceptsEntityRecordParsedByAst() {
        // given
        Project project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        writeJava(project, 'com/example/order/adapter/out/persistence/OrderEntity.java', '''
                package com.example.order.adapter.out.persistence;

                import jakarta.persistence.Entity;
                import jakarta.persistence.Table;

                @Entity
                @Table(name = "orders")
                public record OrderEntity() {
                }
                ''')

        // when
        List<String> violations = EntityNamingConventionValidator.validate(
                project,
                new HexagonalConventionExtension()
        )

        // then
        assert violations.isEmpty()
    }

    @Test
    @DisplayName('Table annotation을 선택 사항으로 설정하면 올바른 Entity를 허용한다')
    void acceptsEntityWithoutTableWhenTableAnnotationIsOptional() {
        // given
        Project project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        writeJava(project, 'com/example/order/adapter/out/persistence/OrderEntity.java', '''
                package com.example.order.adapter.out.persistence;

                import jakarta.persistence.Entity;

                @Entity
                public class OrderEntity {
                }
                ''')
        HexagonalConventionExtension convention = new HexagonalConventionExtension()
        convention.requireTableAnnotation = false

        // when
        List<String> violations = EntityNamingConventionValidator.validate(project, convention)

        // then
        assert violations.isEmpty()
    }

    @Test
    @DisplayName('줄바꿈된 fully-qualified Entity annotation도 Domain 배치 위반으로 거부한다')
    void rejectsMultilineQualifiedEntityAnnotationInDomain() {
        // given
        Project project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        writeJava(project, 'com/example/order/domain/model/OrderEntity.java', '''
                package com.example.order.domain.model;

                @jakarta.persistence
                    .Entity
                @jakarta.persistence.Table(
                    name = "orders"
                )
                public class OrderEntity {
                }
                ''')

        // when
        List<String> violations = EntityNamingConventionValidator.validate(
                project,
                new HexagonalConventionExtension()
        )

        // then
        assert violations.any { it.contains('uses @Entity in domain package') }
        assert !violations.any { it.contains('must declare @Table') }
    }

    private static void writeJava(Project project, String path, String source) {
        File file = new File(project.projectDir, "src/main/java/${path}")
        file.parentFile.mkdirs()
        file.text = source.stripIndent().trim() + System.lineSeparator()
    }
}
