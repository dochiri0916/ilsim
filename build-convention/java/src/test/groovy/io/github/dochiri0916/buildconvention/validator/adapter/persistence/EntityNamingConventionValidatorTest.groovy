package io.github.dochiri0916.buildconvention.validator.adapter.persistence

import io.github.dochiri0916.buildconvention.extension.HexagonalConventionExtension
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

        List<String> violations = EntityNamingConventionValidator.validate(
                project,
                new HexagonalConventionExtension()
        )

        assert violations.any { it.contains("JPA entity 'OrderRecord' must end with Entity") }
    }

    @Test
    @DisplayName('같은 파일의 보조 top-level Entity 타입도 이름 규칙으로 검증한다')
    void validatesSecondaryTopLevelEntityInSameFile() {
        Project project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        writeJava(project, 'com/example/order/adapter/out/persistence/OrderEntity.java', '''
                package com.example.order.adapter.out.persistence;

                import jakarta.persistence.Entity;
                import jakarta.persistence.Table;

                @Entity
                @Table(name = "orders")
                public class OrderEntity {
                }

                @Entity
                @Table(name = "hidden")
                class HiddenRecord {
                }
                ''')

        List<String> violations = EntityNamingConventionValidator.validate(
                project,
                new HexagonalConventionExtension()
        )

        assert violations.any { it.contains("JPA entity 'HiddenRecord' must end with Entity") }
    }

    @Test
    @DisplayName('Domain Entity와 Table annotation 누락을 함께 거부한다')
    void rejectsDomainEntityWithoutTableAnnotation() {
        Project project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        writeJava(project, 'com/example/order/domain/model/OrderEntity.java', '''
                package com.example.order.domain.model;

                import jakarta.persistence.Entity;

                @Entity
                public class OrderEntity {
                }
                ''')

        List<String> violations = EntityNamingConventionValidator.validate(
                project,
                new HexagonalConventionExtension()
        )

        assert violations.any { it.contains('uses @Entity in domain package') }
        assert violations.any { it.contains("entity 'OrderEntity' must declare @Table") }
    }

    @Test
    @DisplayName('Entity record도 AST에서 타입 이름과 Table annotation을 인식한다')
    void acceptsEntityRecordParsedByAst() {
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

        List<String> violations = EntityNamingConventionValidator.validate(
                project,
                new HexagonalConventionExtension()
        )

        assert violations.isEmpty()
    }

    @Test
    @DisplayName('Table annotation을 선택 사항으로 설정하면 올바른 Entity를 허용한다')
    void acceptsEntityWithoutTableWhenTableAnnotationIsOptional() {
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

        List<String> violations = EntityNamingConventionValidator.validate(project, convention)

        assert violations.isEmpty()
    }

    @Test
    @DisplayName('줄바꿈된 fully-qualified Entity annotation도 Domain 배치 위반으로 거부한다')
    void rejectsMultilineQualifiedEntityAnnotationInDomain() {
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

        List<String> violations = EntityNamingConventionValidator.validate(
                project,
                new HexagonalConventionExtension()
        )

        assert violations.any { it.contains('uses @Entity in domain package') }
        assert !violations.any { it.contains('must declare @Table') }
    }

    @Test
    @DisplayName('outbox inbox 패키지의 Entry Entity는 Entry 접미사를 허용하고 그 밖에서는 거부한다')
    void allowsEntrySuffixOnlyInOutboxAndInboxPackages() {
        Project project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        writeJava(project, 'com/example/order/adapter/out/persistence/outbox/OrderOutboxEntry.java', '''
                package com.example.order.adapter.out.persistence.outbox;

                import jakarta.persistence.Entity;
                import jakarta.persistence.Table;

                @Entity
                @Table(name = "order_outbox")
                public class OrderOutboxEntry {
                }
                ''')
        writeJava(project, 'com/example/order/adapter/out/persistence/OrderEntry.java', '''
                package com.example.order.adapter.out.persistence;

                import jakarta.persistence.Entity;
                import jakarta.persistence.Table;

                @Entity
                @Table(name = "orders")
                public class OrderEntry {
                }
                ''')

        List<String> violations = EntityNamingConventionValidator.validate(project, new HexagonalConventionExtension())

        assert !violations.any { it.contains("'OrderOutboxEntry'") }
        assert violations.any { it.contains("JPA entity 'OrderEntry' must end with Entity") }
    }

    private static void writeJava(Project project, String path, String source) {
        File file = new File(project.projectDir, "src/main/java/${path}")
        file.parentFile.mkdirs()
        file.text = source.stripIndent().trim() + System.lineSeparator()
    }
}
