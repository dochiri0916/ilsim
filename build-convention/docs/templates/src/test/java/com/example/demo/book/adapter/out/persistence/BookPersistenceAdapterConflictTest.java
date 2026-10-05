package com.example.demo.book.adapter.out.persistence;

import com.example.demo.book.application.exception.DuplicateIsbnException;
import com.example.demo.book.domain.Book;
import com.example.demo.book.domain.vo.BookTitle;
import com.example.demo.book.domain.vo.Isbn;
import com.example.demo.global.application.exception.RepositoryOperationException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BookPersistenceAdapterConflictTest {

    private final BookJpaRepository jpaRepository = mock(BookJpaRepository.class);
    private final BookQueryRepository queryRepository = mock(BookQueryRepository.class);
    private final BookPersistenceAdapter adapter = new BookPersistenceAdapter(jpaRepository, queryRepository);

    @ParameterizedTest
    @ValueSource(strings = {"uk_books_isbn", "books.uk_books_isbn"})
    @DisplayName("제약 이름과 MySQL의 테이블 한정 이름을 정확히 비교하고 원인을 보존한다")
    void translatesExactIsbnConstraint(final String name) {
        // given
        final Book book = Book.create(Isbn.of("9788966260959"), BookTitle.of("클린 코드"));
        final DataIntegrityViolationException failure = failure(name);
        when(jpaRepository.saveAndFlush(any(BookEntity.class))).thenThrow(failure);

        // when & then
        assertThatThrownBy(() -> adapter.create(book))
                .isInstanceOf(DuplicateIsbnException.class)
                .hasCause(failure);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"other_table.uk_books_isbn", "books.uk_books_isbn_suffix", "books.uk_books_public_id"})
    @DisplayName("다른 테이블·다른 제약·이름 일부만 일치하는 실패를 ISBN 중복으로 바꾸지 않는다")
    void rejectsOtherConstraints(final String name) {
        // given
        final Book book = Book.create(Isbn.of("9788966260959"), BookTitle.of("클린 코드"));
        final DataIntegrityViolationException failure = failure(name);
        when(jpaRepository.saveAndFlush(any(BookEntity.class))).thenThrow(failure);

        // when & then
        assertThatThrownBy(() -> adapter.create(book))
                .isInstanceOf(RepositoryOperationException.class)
                .hasCause(failure);
    }

    private static DataIntegrityViolationException failure(final String name) {
        final SQLException sql = new SQLException("중복", "23000", 1062);
        final ConstraintViolationException violation = new ConstraintViolationException("중복", sql, name);
        return new DataIntegrityViolationException("저장 실패", violation);
    }

}
