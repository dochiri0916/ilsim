package com.example.demo.book.adapter.out.persistence;

import com.example.demo.book.application.exception.DuplicateIsbnException;
import com.example.demo.book.application.port.out.BookRepositoryPort;
import com.example.demo.book.domain.Book;
import com.example.demo.book.domain.vo.BookTitle;
import com.example.demo.book.domain.vo.Isbn;
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
        final Book book = Book.create(new Isbn("9788966260959"), new BookTitle("클린 코드"));

        // when
        bookRepositoryPort.create(book);

        // then
        assertThat(bookRepositoryPort.findByBookId(book.id().value()))
                .hasValueSatisfying(found -> assertThat(found.title().value()).isEqualTo("클린 코드"));
        assertThat(bookRepositoryPort.existsByIsbn("9788966260959")).isTrue();
        assertThat(bookRepositoryPort.existsByIsbn("9780000000000")).isFalse();
        assertThat(bookRepositoryPort.findByBookId("missing")).isEmpty();
    }

    @Test
    @DisplayName("같은 ISBN을 두 번 저장하면 중복 예외가 발생한다")
    void duplicateIsbn() {
        // given
        bookRepositoryPort.create(Book.create(new Isbn("9788966260959"), new BookTitle("클린 코드")));
        final Book duplicate = Book.create(new Isbn("9788966260959"), new BookTitle("다른 제목"));

        // when & then
        assertThatThrownBy(() -> bookRepositoryPort.create(duplicate))
                .isInstanceOf(DuplicateIsbnException.class);
    }

}
