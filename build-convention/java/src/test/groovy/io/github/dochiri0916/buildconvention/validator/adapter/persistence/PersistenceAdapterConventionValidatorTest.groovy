package io.github.dochiri0916.buildconvention.validator.adapter.persistence

import io.github.dochiri0916.buildconvention.source.java.JavaSourceAstInspector
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PersistenceAdapterConventionValidatorTest {
    @TempDir File directory

    private static final String SOURCE = '''
        package com.example.book.adapter.out.persistence;
        import static com.example.global.persistence.RepositoryExceptionTranslator.execute;
        import static com.example.global.persistence.RepositoryExceptionTranslator.isConstraintViolation;
        public final class BookPersistenceAdapter {
            private final BookJpaRepository repository = null;
            public Book create(final Book book) {
                return execute(() -> {
                    try { return repository.saveAndFlush(book); }
                    catch (final DataIntegrityViolationException exception) { throw conflict(exception); }
                });
            }
            private static RuntimeException conflict(final DataIntegrityViolationException exception) {
                if (isConstraintViolation(exception, "uk_books_isbn")) return new DuplicateIsbnException(exception);
                return exception;
            }
        }
    '''

    @Test
    @DisplayName('flush와 정확한 제약 확인 및 원인을 보존하는 로컬 변환을 허용한다')
    void accepts_flushed_constraint_specific_conversion() {
        // given
        // when
        def violations = validate(SOURCE)
        // then
        assert violations.empty
        assert validate(SOURCE.replace('return repository.saveAndFlush(book);',
                'Book saved = repository.save(book); repository.flush(); return saved;')).empty
        assert validate(SOURCE.replace('"uk_books_isbn"', 'UNIQUE_ISBN')
                .replace('private final BookJpaRepository', 'private static final String UNIQUE_ISBN = "uk_books_isbn"; private final BookJpaRepository')).empty
    }

    @Test
    @DisplayName('조회 경계 누락·저장 후 flush 누락·문자열 속 가짜 flush를 거부한다')
    void rejects_missing_translation_and_flush() {
        // given
        // when
        def noFlush = validate(SOURCE.replace('repository.saveAndFlush(book)', 'repository.save(book)'))
        def fakeFlush = validate(SOURCE.replace('return repository.saveAndFlush(book);',
                'String hint = "repository.flush()"; return repository.save(book);'))
        def fakeTranslator = validate(SOURCE.replace('com.example.global.persistence.RepositoryExceptionTranslator.execute', 'fake.RepositoryExceptionTranslator.execute'))
        // then
        assert noFlush.any { it.contains('must flush') }
        assert fakeFlush.any { it.contains('must flush') }
        assert fakeTranslator.any { it.contains('must wrap query execution') }
    }

    @Test
    @DisplayName('모든 무결성 오류를 업무 중복으로 바꾸거나 원인을 버릴 수 없다')
    void rejects_broad_conflicts_and_lost_causes() {
        // given
        // when
        def broad = validate(SOURCE.replace('if (isConstraintViolation(exception, "uk_books_isbn"))', 'if (true)'))
        def lost = validate(SOURCE.replace('new DuplicateIsbnException(exception)', 'new DuplicateIsbnException()'))
        // then
        assert broad.any { it.contains('exact named constraint') }
        assert lost.any { it.contains('preserve its original exception') }
    }

    @Test
    @DisplayName('실패한 catch 안의 저장 재시도와 별칭을 통한 flush 우회를 거부한다')
    void rejects_retry_and_alias_bypasses() {
        // given
        // when
        def retry = validate(SOURCE.replace('throw conflict(exception);', 'return repository.saveAndFlush(book);'))
        def alias = validate(SOURCE.replace('return repository.saveAndFlush(book);',
                'var storage = repository; return storage.save(book);'))
        // then
        assert retry.any { it.contains('must not retry a write') }
        assert alias.any { it.contains('must flush') }
    }

    @Test
    @DisplayName('헬퍼를 통한 조회도 번역하고 순환 호출이 검사를 숨기지 못한다')
    void inspects_helper_and_recursive_calls() {
        // given
        String source = '''
            package com.example.book.adapter.out.persistence;
            import static com.example.global.persistence.RepositoryExceptionTranslator.execute;
            public final class BookPersistenceAdapter {
                private final BookJpaRepository repository = null;
                public Book find() { return execute(() -> load()); }
                private Book load() { return repository.findById(); }
            }
        '''
        // when
        def valid = validate(source)
        def invalid = validate(source.replace('return execute(() -> load());', 'return load();')
                .replace('return repository.findById();', 'load(); return repository.findById();'))
        // then
        assert valid.empty
        assert invalid.any { it.contains('must wrap query execution') }
    }

    @Test
    @DisplayName('RepositoryExceptionTranslator.run으로 감싼 쓰기도 번역 경계로 인정한다')
    void accepts_translator_run_as_translation_boundary() {
        // given
        String source = '''
            package com.example.book.adapter.out.persistence;
            import static com.example.global.persistence.RepositoryExceptionTranslator.run;
            public final class BookPersistenceAdapter {
                private final BookJpaRepository repository = null;
                public void delete() { run(() -> { repository.deleteByPublicId(); repository.flush(); }); }
            }
        '''

        // when
        def violations = validate(source)

        // then
        assert violations.empty
    }

    private List<String> validate(String source) {
        File file = new File(directory, 'BookPersistenceAdapter.java')
        file.text = source
        def inspection = JavaSourceAstInspector.inspect(file)
        assert inspection.valid: inspection.errors
        return PersistenceAdapterConventionValidator.validate(file.name, inspection.packageName,
                inspection.primaryType(), inspection.allImports)
    }
}
