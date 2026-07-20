package com.dochiri.convention.validator

import com.dochiri.convention.extension.HexagonalConventionExtension
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DomainAggregateConventionValidatorTest {

    @TempDir
    File tempDir

    @Test
    @DisplayName('Product 형태의 불변 Aggregate와 식별자 컬렉션을 허용한다')
    void acceptsImmutableAggregateWithIdentifierCollection() {
        // given
        Project project = sampleProject('valid-product')
        writeProductSupportTypes(project)
        writeJava(project, 'com/example/catalog/domain/model/Product.java', '''
                package com.example.catalog.domain.model;

                import java.util.Objects;
                import java.util.Set;

                public final class Product {
                    private final ProductId id;
                    private final ProductName name;
                    private final Set<CategoryId> categoryIds;

                    private Product(final ProductId id, final ProductName name, final Set<CategoryId> categoryIds) {
                        this.id = Objects.requireNonNull(id);
                        this.name = Objects.requireNonNull(name);
                        this.categoryIds = Set.copyOf(categoryIds);
                    }

                    public static Product create(final String name, final Set<CategoryId> categoryIds) {
                        return new Product(ProductId.generate(), ProductName.from(name), categoryIds);
                    }

                    public static Product restore(final ProductId id, final ProductName name, final Set<CategoryId> categoryIds) {
                        return new Product(id, name, categoryIds);
                    }

                    @Override
                    public boolean equals(final Object other) {
                        return other instanceof Product product && id.equals(product.id);
                    }

                    @Override
                    public int hashCode() {
                        return id.hashCode();
                    }
                }
                ''')

        // when
        List<String> violations = JavaSourceArchitectureValidator.validate(project, new HexagonalConventionExtension())

        // then
        assert !violations.any { violation -> violation.contains('Product.java') }
    }

    @Test
    @DisplayName('원시 원소 컬렉션과 방어 복사 누락을 거부한다')
    void rejectsRawElementCollectionAndMissingDefensiveCopy() {
        // given
        Project project = sampleProject('invalid-product')
        writeProductSupportTypes(project)
        writeJava(project, 'com/example/catalog/domain/model/Product.java', '''
                package com.example.catalog.domain.model;

                import java.util.Set;

                public final class Product {
                    private final ProductId id;
                    private final Set<String> categoryIds;

                    private Product(final ProductId id, final Set<String> categoryIds) {
                        this.id = id;
                        this.categoryIds = categoryIds;
                    }

                    public static Product create(final Set<String> categoryIds) {
                        return new Product(ProductId.generate(), categoryIds);
                    }

                    @Override
                    public boolean equals(final Object other) {
                        return other instanceof Product product && id.equals(product.id);
                    }
                }
                ''')

        // when
        List<String> violations = JavaSourceArchitectureValidator.validate(project, new HexagonalConventionExtension())

        // then
        assert violations.any { violation -> violation.contains("collection field 'categoryIds' must contain") }
        assert violations.any { violation -> violation.contains("collection field 'categoryIds' must defensively copy with Set.copyOf") }
        assert violations.any { violation -> violation.contains('aggregate with identifier VO must override equals and hashCode') }
    }

    @Test
    @DisplayName('Map의 key와 value Value Object 및 이름이 다른 생성자 인자를 허용한다')
    void acceptsMapValueObjectsAndRenamedConstructorParameter() {
        // given
        Project project = sampleProject('map-value-objects')
        writeProductSupportTypes(project)
        writeJava(project, 'com/example/catalog/domain/model/Tag.java', '''
                package com.example.catalog.domain.model;
                public record Tag(String value) {
                }
                ''')
        writeJava(project, 'com/example/catalog/domain/model/Product.java', '''
                package com.example.catalog.domain.model;

                import java.util.Map;

                public final class Product {
                    private final ProductId id;
                    private final Map<CategoryId, Tag> tags;

                    private Product(final ProductId id, final Map<CategoryId, Tag> values) {
                        this.id = id;
                        this.tags = Map.copyOf(values);
                    }

                    public static Product create(final Map<CategoryId, Tag> tags) {
                        return new Product(ProductId.generate(), tags);
                    }

                    @Override
                    public boolean equals(final Object other) {
                        return other instanceof Product product && id.equals(product.id);
                    }

                    @Override
                    public int hashCode() {
                        return id.hashCode();
                    }
                }
                ''')

        // when
        List<String> violations = JavaSourceArchitectureValidator.validate(project, new HexagonalConventionExtension())

        // then
        assert !violations.any { violation -> violation.contains("Product.java domain collection field 'tags'") }
    }

    @Test
    @DisplayName('Aggregate array는 validator 오류 대신 명확한 위반으로 보고한다')
    void rejectsArrayCollectionWithoutCrashing() {
        // given
        Project project = sampleProject('array-collection')
        writeProductSupportTypes(project)
        writeJava(project, 'com/example/catalog/domain/model/Product.java', '''
                package com.example.catalog.domain.model;

                public final class Product {
                    private final ProductId id;
                    private final CategoryId[] categoryIds;

                    private Product(final ProductId id, final CategoryId[] categoryIds) {
                        this.id = id;
                        this.categoryIds = categoryIds;
                    }

                    public static Product create(final CategoryId[] categoryIds) {
                        return new Product(ProductId.generate(), categoryIds);
                    }

                    @Override
                    public boolean equals(final Object other) {
                        return other instanceof Product product && id.equals(product.id);
                    }

                    @Override
                    public int hashCode() {
                        return id.hashCode();
                    }
                }
                ''')

        // when
        List<String> violations = JavaSourceArchitectureValidator.validate(project, new HexagonalConventionExtension())

        // then
        assert violations.any { violation ->
            violation.contains("collection field 'categoryIds' must use List, Set, Map")
        }
    }

    @Test
    @DisplayName('식별자를 가진 Domain record Aggregate를 거부한다')
    void rejectsRecordAggregate() {
        // given
        Project project = sampleProject('record-aggregate')
        writeProductSupportTypes(project)
        writeJava(project, 'com/example/catalog/domain/model/Product.java', '''
                package com.example.catalog.domain.model;

                public record Product(ProductId id, ProductName name) {

                    public Product {
                        if (id == null || name == null) {
                            throw new IllegalArgumentException();
                        }
                    }
                }
                ''')

        // when
        List<String> violations = JavaSourceArchitectureValidator.validate(project, new HexagonalConventionExtension())

        // then
        assert violations.any { violation ->
            violation.contains("domain aggregate 'Product' must be a final class, not a record")
        }
    }

    @Test
    @DisplayName('문자열 Value Object record의 null과 blank 불변식을 모두 요구한다')
    void rejectsStringValueObjectWithoutBlankInvariant() {
        // given
        Project project = sampleProject('invalid-value-object')
        writeJava(project, 'com/example/catalog/domain/model/ProductName.java', '''
                package com.example.catalog.domain.model;

                public record ProductName(String value) {

                    public ProductName {
                        if (value == null) {
                            throw new IllegalArgumentException();
                        }
                    }
                }
                ''')

        // when
        List<String> violations = JavaSourceArchitectureValidator.validate(project, new HexagonalConventionExtension())

        // then
        assert violations.any { violation ->
            violation.contains("domain record 'ProductName' must null-check and blank-check String components")
        }
    }

    @Test
    @DisplayName('무상태 Domain Service는 final class로 허용한다')
    void acceptsFinalDomainService() {
        // given
        Project project = sampleProject('final-domain-service')
        writeJava(project, 'com/example/catalog/domain/model/PricingService.java', '''
                package com.example.catalog.domain.model;

                public final class PricingService {
                }
                ''')

        // when
        List<String> violations = JavaSourceArchitectureValidator.validate(project, new HexagonalConventionExtension())

        // then
        assert !violations.any { violation ->
            violation.contains("domain service 'PricingService' must be final")
        }
    }

    @Test
    @DisplayName('비공개 equals는 Aggregate의 ID 동등성 구현으로 인정하지 않는다')
    void rejectsAggregateWithNonPublicEquals() {
        // given
        Project project = sampleProject('non-public-equals')
        writeProductSupportTypes(project)
        writeJava(project, 'com/example/catalog/domain/model/Product.java', '''
                package com.example.catalog.domain.model;

                public final class Product {
                    private final ProductId id;

                    private Product(final ProductId id) {
                        this.id = id;
                    }

                    public static Product create() {
                        return new Product(ProductId.generate());
                    }

                    private boolean equals(final Object other) {
                        return other instanceof Product product && id.equals(product.id);
                    }

                    @Override
                    public int hashCode() {
                        return id.hashCode();
                    }
                }
                ''')

        // when
        List<String> violations = JavaSourceArchitectureValidator.validate(project, new HexagonalConventionExtension())

        // then
        assert violations.any { violation ->
            violation.contains('aggregate with identifier VO must override equals and hashCode')
        }
    }

    @Test
    @DisplayName('Aggregate의 가변 상태와 ID 이외의 동등성 및 restore 부작용을 거부한다')
    void rejectsMutableStateNonIdEqualityAndRestoreSideEffects() {
        // given
        Project project = sampleProject('invalid-aggregate-semantics')
        writeProductSupportTypes(project)
        writeJava(project, 'com/example/catalog/domain/model/Product.java', '''
                package com.example.catalog.domain.model;

                import java.time.Instant;
                import java.util.UUID;

                public final class Product {
                    private final ProductId id;
                    private ProductName name;

                    private Product(final ProductId id, final ProductName name) {
                        this.id = id;
                        this.name = name;
                    }

                    public static Product restore(final ProductId id, final ProductName name) {
                        UUID.randomUUID();
                        Instant.now();
                        return new Product(id, name);
                    }

                    public Product rename(final ProductName name) {
                        this.name = name;
                        return this;
                    }

                    @Override
                    public boolean equals(final Object other) {
                        return other instanceof Product product
                                && id.equals(product.id)
                                && name.equals(product.name);
                    }

                    @Override
                    public int hashCode() {
                        return name.hashCode();
                    }
                }
                ''')

        // when
        List<String> violations = JavaSourceArchitectureValidator.validate(project, new HexagonalConventionExtension())

        // then
        assert violations.any { it.contains('state must use private final fields') }
        assert violations.any { it.contains("equals and hashCode must use identifier field 'id' only") }
        assert violations.any { it.contains('restore must not create a new id, time, UUID, or domain event') }
        assert violations.any { it.contains('state changes must return a new aggregate') }
    }

    private Project sampleProject(String name) {
        File projectDir = new File(tempDir, name)
        projectDir.mkdirs()
        return ProjectBuilder.builder().withProjectDir(projectDir).build()
    }

    private static void writeProductSupportTypes(Project project) {
        writeJava(project, 'com/example/catalog/domain/model/ProductId.java', '''
                package com.example.catalog.domain.model;
                public record ProductId() {
                    public static ProductId generate() { return new ProductId(); }
                }
                ''')
        writeJava(project, 'com/example/catalog/domain/model/ProductName.java', '''
                package com.example.catalog.domain.model;
                public record ProductName() {
                    public static ProductName from(final String value) { return new ProductName(); }
                }
                ''')
        writeJava(project, 'com/example/catalog/domain/model/CategoryId.java', '''
                package com.example.catalog.domain.model;
                public record CategoryId() {
                }
                ''')
    }

    private static void writeJava(Project project, String path, String source) {
        File file = new File(project.projectDir, "src/main/java/${path}")
        file.parentFile.mkdirs()
        file.text = source.stripIndent().trim() + System.lineSeparator()
    }
}
