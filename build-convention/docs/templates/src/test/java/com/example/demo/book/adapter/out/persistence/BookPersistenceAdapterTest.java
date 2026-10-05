package com.example.demo.book.adapter.out.persistence;

import com.example.demo.book.domain.vo.BookId;

import com.example.demo.book.application.exception.DuplicateIsbnException;
import com.example.demo.book.application.port.out.BookRepositoryPort;
import com.example.demo.book.domain.Book;
import com.example.demo.book.domain.vo.BookTitle;
import com.example.demo.book.domain.vo.Isbn;
import com.example.demo.global.application.exception.RepositoryOperationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class BookPersistenceAdapterTest {

    @Autowired
    private BookRepositoryPort bookRepositoryPort;

    @Test
    @DisplayName("저장한 도서를 ID로 다시 읽고 ISBN 존재 여부를 확인한다")
    void createAndFind() {
        // given
        final Book book = Book.create(Isbn.of("9788966260959"), BookTitle.of("클린 코드"));

        // when
        bookRepositoryPort.create(book);

        // then
        assertThat(bookRepositoryPort.findByBookId(book.id()))
                .hasValueSatisfying(found -> assertThat(found.title().value()).isEqualTo("클린 코드"));
        assertThat(bookRepositoryPort.existsByIsbn(Isbn.of("9788966260959"))).isTrue();
        assertThat(bookRepositoryPort.existsByIsbn(Isbn.of("9780000000000"))).isFalse();
        assertThat(bookRepositoryPort.findByBookId(BookId.parse("01900000-0000-7000-8000-000000000099"))).isEmpty();
    }

    @Test
    @DisplayName("같은 ISBN을 두 번 저장하면 중복 예외가 발생한다")
    void duplicateIsbn() {
        // given
        bookRepositoryPort.create(Book.create(Isbn.of("9788966260959"), BookTitle.of("클린 코드")));
        final Book duplicate = Book.create(Isbn.of("9788966260959"), BookTitle.of("다른 제목"));

        // when & then
        assertThatThrownBy(() -> bookRepositoryPort.create(duplicate))
                .isInstanceOf(DuplicateIsbnException.class)
                .hasCauseInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("자체 publicId 충돌은 ISBN 중복으로 바꾸지 않고 원인 있는 저장소 오류로 전달한다")
    void preserves_other_constraint_failures() {
        // given
        final Book original = Book.create(Isbn.of("9788966260959"), BookTitle.of("첫 도서"));
        bookRepositoryPort.create(original);
        final Book sameId = Book.restore(original.id(), Isbn.of("9780000000000"), BookTitle.of("다른 도서"));

        // when & then
        assertThatThrownBy(() -> bookRepositoryPort.create(sameId))
                .isInstanceOf(RepositoryOperationException.class)
                .hasCauseInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

}
