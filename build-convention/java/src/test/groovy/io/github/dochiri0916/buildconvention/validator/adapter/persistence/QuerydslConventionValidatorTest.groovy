package io.github.dochiri0916.buildconvention.validator.adapter.persistence

import io.github.dochiri0916.buildconvention.extension.HexagonalConventionExtension
import io.github.dochiri0916.buildconvention.source.java.JavaSourceAstInspector
import io.github.dochiri0916.buildconvention.validator.architecture.JavaSourceArchitectureValidator
import io.github.dochiri0916.buildconvention.validator.quality.ChangedCodeCoverageValidator
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class QuerydslConventionValidatorTest {
    @TempDir File directory

    @Test
    @DisplayName('QueryRepository는 영속성 패키지의 구체 Component로 둔다')
    void requires_component_repository_placement() {
        String valid = 'import org.springframework.stereotype.Component; @Component public final class BookQueryRepository {}'
        assert validate(valid).empty
        assert validate(valid.replace('final class', 'interface')).any { it.contains('concrete @Component') }
        assert validate(valid.replace('@Component', '@fake.Component')).any { it.contains('concrete @Component') }
        assert validate(valid, 'com.example.book.application.service').any { it.contains('adapter.out.persistence') }
    }

    @Test
    @DisplayName('QueryProjection과 constructor 매핑은 public Row record에만 허용한다')
    void restricts_internal_projection_contracts() {
        String row = 'import com.querydsl.core.annotations.QueryProjection; public record BookRow(String title) { @QueryProjection public BookRow {} }'
        String wrong = 'import com.querydsl.core.annotations.QueryProjection; public record BookResponse(String title) { @QueryProjection public BookResponse {} }'
        assert validate(row).empty
        assert validate(row.replace('public record', 'record')).any { it.contains('public record') }
        assert validate(wrong).any { it.contains('only allowed') }
        assert validate('import com.querydsl.core.types.Projections; public final class Reader { void read() { Projections.constructor(BookResponse.class, title); } }')
                .any { it.contains('constructor projection must target') }
    }

    @Test
    @DisplayName('폐기된 fetchResults와 fetchCount는 문자열·주석과 구별한다')
    void rejects_deprecated_calls_without_literal_false_positives() {
        String valid = 'public final class Reader { void read() { String hint = "query.fetchResults()"; /* query.fetchCount(); */ query.fetch(); } }'
        assert validate(valid).empty
        assert validate(valid.replace('query.fetch();', 'query.fetchResults();')).any { it.contains('separate list/count queries') }
        assert validate(valid.replace('query.fetch();', 'query.fetchCount();')).any { it.contains('separate list/count queries') }
    }

    @Test
    @DisplayName('QueryFactory는 공통 구성에서 만들고 외부에는 변경 가능한 쿼리를 돌려주지 않는다')
    void restricts_factory_creation_and_query_leaks() {
        String construction = '''
            import org.springframework.context.annotation.Configuration;
            import org.springframework.context.annotation.Bean;
            import jakarta.persistence.EntityManager;
            import com.querydsl.jpa.impl.JPAQueryFactory;
            @Configuration(proxyBeanMethods = false) public final class QuerydslConfiguration {
                @Bean public JPAQueryFactory factory(final EntityManager manager) { return new JPAQueryFactory(manager); }
            }
        '''
        String leak = '''
            import org.springframework.stereotype.Component;
            @Component public final class BookQueryRepository { public JPAQuery<BookEntity> query() { return factory.selectFrom(book); } }
        '''
        assert validate(construction, 'com.example.global.configuration').empty
        assert validate(construction).any { it.contains('must be injected') }
        assert validate(leak).any { it.contains('exposing mutable JPAQuery') }
        assert validate(construction.replace('@Bean', ''), 'com.example.global.configuration').any { it.contains("Spring's injected EntityManager") }
        assert validate(construction.replace('new JPAQueryFactory(manager)', 'new JPAQueryFactory(managerFactory.createEntityManager())'),
                'com.example.global.configuration').any { it.contains("Spring's injected EntityManager") }
        assert validate('public final class Reader { private final JPAQueryFactory factory = new JPAQueryFactory(manager); }')
                .any { it.contains('must be injected') }
    }

    @Test
    @DisplayName('페이징은 정렬을 선언하며 컬렉션 fetch join을 함께 쓰지 않는다')
    void rejects_paging_without_order_and_collection_fetch_joins() {
        String prefix = 'import org.springframework.stereotype.Component; @Component public final class BookQueryRepository { '
        String plain = prefix + 'void page() { query.offset(0); query.limit(10); query.orderBy(book.id.desc()); query.fetch(); } }'
        String collection = prefix + 'void page(final ListPath<Book, QBook> children) { query.leftJoin(children); query.fetchJoin(); query.limit(10); query.orderBy(book.id.desc()); query.fetch(); } }'
        assert validate(plain).empty
        assert validate(plain.replace('query.orderBy(book.id.desc());', '')).any { it.contains('pagination must declare an order') }
        assert validate(collection).any { it.contains('must not combine collection fetch joins') }
        assert validate(collection.replace('ListPath<Book, QBook>', 'EntityPath<Book>')).empty
    }

    @Test
    @DisplayName('다른 BC의 Entity와 저장소 및 보관된 가변 쿼리를 거부한다')
    void rejects_foreign_storage_and_retained_queries() {
        String source = '''
            import org.springframework.stereotype.Component;
            import com.example.member.adapter.out.persistence.MemberEntity;
            import com.example.member.application.port.out.MemberRepositoryPort;
            @Component public final class BookQueryRepository { private final JPAQuery<Book> query = null; }
        '''
        def violations = validate(source)
        assert violations.count { it.contains("another bounded context's Entity or repository") } == 2
        assert violations.any { it.contains('retaining mutable JPAQuery state') }
    }

    @Test
    @DisplayName('같은 이름의 Row도 실제 public record와 영속성 소유 패키지를 확인한다')
    void resolves_actual_projection_rows() {
        String source = 'import com.querydsl.core.types.Projections; public final class Reader { void read() { Projections.constructor(BookRow.class, title); } }'
        File row = new File(directory, 'BookRow.java')
        row.text = 'package com.example.book.adapter.out.persistence; public record BookRow(String title) {}'
        def model = JavaSourceAstInspector.inspect(row).primaryType()
        assert validate(source, 'com.example.book.adapter.out.persistence', [(model.qualifiedName): model]).empty
        row.text = 'package com.example.book.adapter.out.persistence; public final class BookRow {}'
        def wrong = JavaSourceAstInspector.inspect(row).primaryType()
        assert validate(source, 'com.example.book.adapter.out.persistence', [(wrong.qualifiedName): wrong])
                .any { it.contains('constructor projection must target') }
    }

    @Test
    @DisplayName('페이징 정렬에는 유일 키가 필요하며 지역 별칭과 헬퍼도 확인한다')
    void requires_unique_order_including_aliases_and_helpers() {
        String source = 'import org.springframework.stereotype.Component; @Component public final class BookQueryRepository { void page() { query.limit(10); query.orderBy(book.createdAt.desc(), book.id.desc()); } }'
        assert validate(source).empty
        assert validate(source.replace(', book.id.desc()', '')).any { it.contains('including a unique key') }
        assert validate(source.replace('query.orderBy', 'String hint = "book.id.desc()"; query.orderBy').replace(', book.id.desc()', '')).any { it.contains('including a unique key') }
        assert validate(source.replace('query.orderBy', 'final var tie = book.id.desc(); query.orderBy').replace(', book.id.desc()', ', tie')).empty
        assert validate(source.replace('query.orderBy', 'final NumberPath<Long> key = book.id; query.orderBy').replace('book.id.desc()', 'key.desc()')).empty
        assert validate(source.replace('query.orderBy', 'final StringPath key = book.title; query.orderBy').replace('book.id.desc()', 'key.desc()')).any { it.contains('including a unique key') }
        String helper = source.replace('book.id.desc()', 'tie()').replace('} }', '} private Object tie() { return book.publicId.asc(); } }')
        assert validate(helper).empty
        assert validate(helper.replace('book.publicId.asc()', 'tie()')).any { it.contains('including a unique key') }
        assert validate(helper.replace('return book.publicId.asc();', 'if (flag) return book.id.desc(); return book.title.asc();')).any { it.contains('including a unique key') }
    }

    @Test
    @DisplayName('offset 곱셈 전에 long으로 확장하며 사후 캐스팅은 거부한다')
    void rejects_integer_offset_overflow_and_late_casts() {
        String source = 'import org.springframework.stereotype.Component; @Component public final class BookQueryRepository { void page(final int page, final int size) { query.offset((long) page * size); query.limit(size); query.orderBy(book.id.desc()); } }'
        assert validate(source).empty
        assert validate(source.replace('(long) page * size', 'page * size')).any { it.contains('widen operands') }
        assert validate(source.replace('(long) page * size', '(long) (page * size)')).any { it.contains('widen operands') }
        assert validate(source.replace('(long) page * size', 'page * (long) size')).empty
        assert validate(source.replace('final int page', 'final long page').replace('(long) page', 'page')).empty
        assert validate(source.replace('(long) page * size', '1L * page * size')).empty
        assert validate(source.replace('(long) page * size', 'page * size * 1L')).any { it.contains('widen operands') }
        String alias = source.replace('query.offset((long) page * size);', 'final long offset = page * size; query.offset(offset);')
        assert validate(alias).any { it.contains('widen operands') }
        assert validate(alias.replace('offset = page * size', 'offset = (long) page * size')).empty
        String helper = source.replace('(long) page * size', 'offset(page, size)').replace('} }', '} private long offset(final int page, final int size) { return page * size; } }')
        assert validate(helper).any { it.contains('widen operands') }
        assert validate(helper.replace('return page * size;', 'return (long) page * size;')).empty

    }

    @Test
    @DisplayName('Q 경로가 가리키는 Entity의 단일 열 유일 제약도 정렬 키로 허용한다')
    void accepts_declared_unique_keys_without_accepting_composite_parts() {
        File entityFile = new File(directory, 'BookEntity.java')
        entityFile.text = 'package com.example.book.adapter.out.persistence; import jakarta.persistence.*; @Entity @Table(uniqueConstraints = @UniqueConstraint(name = "uk_isbn", columnNames = "isbn")) public class BookEntity { @Column(nullable = false) private String isbn; }'
        def entity = JavaSourceAstInspector.inspect(entityFile).primaryType()
        String source = 'import org.springframework.stereotype.Component; @Component public final class BookQueryRepository { void page(final QBookEntity book) { query.limit(10); query.orderBy(book.isbn.asc()); } }'
        assert validate(source, 'com.example.book.adapter.out.persistence', [(entity.qualifiedName): entity]).empty
        entityFile.text = entityFile.text.replace('nullable = false', 'nullable = true')
        def nullable = JavaSourceAstInspector.inspect(entityFile).primaryType()
        assert validate(source, 'com.example.book.adapter.out.persistence', [(nullable.qualifiedName): nullable]).any { it.contains('including a unique key') }
        entityFile.text = entityFile.text.replace('nullable = true', 'nullable = false')

        entityFile.text = entityFile.text.replace('columnNames = "isbn"', 'columnNames = {"isbn", "title"}')
        def composite = JavaSourceAstInspector.inspect(entityFile).primaryType()
        assert validate(source, 'com.example.book.adapter.out.persistence', [(composite.qualifiedName): composite]).any { it.contains('including a unique key') }
    }

    @Test
    @DisplayName('기본 Q 인스턴스는 static import하며 역할별 새 별칭과 문자열은 구별한다')
    void requires_static_default_q_reference_without_fake_type_matches() {
        File entityFile = new File(directory, 'BookEntity.java')
        entityFile.text = 'package com.example.book.adapter.out.persistence; @jakarta.persistence.Entity public class BookEntity {}'
        def entity = JavaSourceAstInspector.inspect(entityFile).primaryType()
        String source = 'public final class Reader { void read() { query.from(QBookEntity.bookEntity); } }'
        Map known = [(entity.qualifiedName): entity]

        assert validate(source, 'com.example.book.adapter.out.persistence', known).any { it.contains('must use a static import') }
        assert validate(source.replace('QBookEntity.bookEntity', 'com.example.book.adapter.out.persistence.QBookEntity.bookEntity'), 'com.example.book.adapter.out.persistence', known).any { it.contains('must use a static import') }
        assert validate(source.replace('QBookEntity.bookEntity', 'bookEntity'), 'com.example.book.adapter.out.persistence', known).empty
        assert validate(source.replace('query.from(QBookEntity.bookEntity)', 'QBookEntity buyer = new QBookEntity("buyer"); query.from(buyer)'), 'com.example.book.adapter.out.persistence', known).empty
        assert validate(source.replace('query.from(QBookEntity.bookEntity)', 'String hint = "QBookEntity.bookEntity"; /* QBookEntity.bookEntity */ query.fetch()'), 'com.example.book.adapter.out.persistence', known).empty
        assert validate(source).empty
    }

    @Test
    @DisplayName('Git의 staged Q 소스는 생성 어노테이션 제거와 build 경로 강제 추가로 우회할 수 없다')
    void rejects_git_tracked_q_types_including_generated_paths() {
        def project = ProjectBuilder.builder().withProjectDir(directory).build()
        git('init', '--quiet')
        new File(directory, '.gitignore').text = 'build/\n'
        File source = new File(directory, 'src/main/java/QBookEntity.java')
        source.parentFile.mkdirs()
        source.text = 'import com.querydsl.core.types.dsl.EntityPathBase; public class QBookEntity extends EntityPathBase<BookEntity> {}'

        assert QuerydslConventionValidator.validateTrackedSources(project).empty
        git('add', '--', 'src/main/java/QBookEntity.java')
        assert QuerydslConventionValidator.validateTrackedSources(project).any { it.contains('must not be tracked in Git') }
        source.text = 'public class QBookEntity { String hint = "extends EntityPathBase<BookEntity>"; }'
        assert QuerydslConventionValidator.validateTrackedSources(project).any { it.contains('must not be tracked in Git') }
        git('add', '--', 'src/main/java/QBookEntity.java')
        assert QuerydslConventionValidator.validateTrackedSources(project).empty
        File generated = new File(directory, 'build/generated/sources/annotationProcessor/java/main/QBookRow.java')
        generated.parentFile.mkdirs()
        generated.text = 'import com.querydsl.core.types.ConstructorExpression; public class QBookRow extends ConstructorExpression<BookRow> {}'
        assert QuerydslConventionValidator.validateTrackedSources(project).empty
        git('add', '-f', '--', directory.toPath().relativize(generated.toPath()).toString())
        assert QuerydslConventionValidator.validateTrackedSources(project).any { it.contains('QBookRow.java') }
        generated.delete()
        assert QuerydslConventionValidator.validateTrackedSources(project).any { it.contains('QBookRow.java') }
        File nested = new File(directory, 'src/main/java/Container.java')
        nested.text = 'public class Container { static class QChild extends com.querydsl.core.types.dsl.EntityPathBase<BookEntity> {} }'
        git('add', '--', 'src/main/java/Container.java')
        assert QuerydslConventionValidator.validateTrackedSources(project).any { it.contains('Container.java') }
        assert JavaSourceArchitectureValidator.validate(project, new HexagonalConventionExtension()).any {
            it.startsWith('[ARCH-QUERYDSL]') && it.contains('must not be tracked in Git')
        }
        source.text = 'public class QBookEntity {'
        git('add', '--', 'src/main/java/QBookEntity.java')
        assert QuerydslConventionValidator.validateTrackedSources(project).any { it.contains('cannot parse indexed source') }

    }

    @Test
    @DisplayName('멀티 프로젝트의 Q Git 검사는 하위 프로젝트 기준 index 경로를 사용한다')
    void audits_only_subproject_index_paths() {
        git('init', '--quiet')
        String code = 'public class QBookEntity extends com.querydsl.core.types.dsl.EntityPathBase<BookEntity> {}'
        new File(directory, 'QBookEntity.java').text = code
        File child = new File(directory, 'feature')
        File source = new File(child, 'src/main/java/QBookEntity.java')
        source.parentFile.mkdirs()
        source.text = code
        git('add', '--', 'QBookEntity.java', 'feature/src/main/java/QBookEntity.java')
        def project = ProjectBuilder.builder().withProjectDir(child).build()

        List<String> violations = QuerydslConventionValidator.validateTrackedSources(project)

        assert violations.size() == 1
        assert violations.first().startsWith('src/main/java/QBookEntity.java ')
        assert violations.first().contains('must not be tracked in Git')
    }

    private void git(String... arguments) {
        def result = ChangedCodeCoverageValidator.runProcess(['git', '-C', directory.absolutePath] + arguments.toList(), directory)
        assert result.exitValue == 0: result.stderr
    }

    private List<String> validate(String source, String packageName = 'com.example.book.adapter.out.persistence', Map knownTypes = [:]) {
        File file = new File(directory, 'Query.java')
        file.text = 'package ' + packageName + ';\n' + source
        def inspection = JavaSourceAstInspector.inspect(file)
        assert inspection.valid: inspection.errors
        return QuerydslConventionValidator.validate(file.name, packageName, inspection.primaryType(), inspection.imports, knownTypes)
    }
}
