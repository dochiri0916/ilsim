package com.example.demo.book.domain;

import com.example.demo.book.domain.exception.BookIdRequiredException;
import com.example.demo.book.domain.exception.BookTitleRequiredException;
import com.example.demo.book.domain.exception.IsbnRequiredException;
import com.example.demo.book.domain.vo.BookId;
import com.example.demo.book.domain.vo.BookTitle;
import com.example.demo.book.domain.vo.Isbn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookTest {

    private static final Isbn ISBN = new Isbn("9788966260959");
    private static final BookTitle TITLE = new BookTitle("클린 코드");

    @Test
    @DisplayName("도서를 만들면 새 ID와 입력한 ISBN, 제목을 가진다")
    void create() {
        // given
        final Isbn isbn = ISBN;

        // when
        final Book book = Book.create(isbn, TITLE);

        // then
        assertThat(book.id().value()).hasSize(32);
        assertThat(book.isbn()).isEqualTo(isbn);
        assertThat(book.title()).isEqualTo(TITLE);
    }

    @Test
    @DisplayName("저장된 상태로 복원하면 같은 ID를 유지한다")
    void restore() {
        // given
        final BookId id = new BookId("book-1");

        // when
        final Book book = Book.restore(id, ISBN, TITLE);

        // then
        assertThat(book.id()).isEqualTo(id);
        assertThat(book).isEqualTo(Book.restore(id, ISBN, new BookTitle("다른 제목")));
        assertThat(book.hashCode()).isEqualTo(id.hashCode());
        assertThat(book).isNotEqualTo(Book.restore(new BookId("book-2"), ISBN, TITLE));
        assertThat(book.equals(book)).isTrue();
        assertThat(book).isNotEqualTo((Object) "book-1");
    }

    @Test
    @DisplayName("제목을 바꾸면 같은 ID의 새 도서를 돌려주고 원래 도서는 그대로다")
    void rename() {
        // given
        final Book book = Book.create(ISBN, TITLE);

        // when
        final Book renamed = book.rename(new BookTitle("클린 아키텍처"));

        // then
        assertThat(renamed).isEqualTo(book);
        assertThat(renamed.title().value()).isEqualTo("클린 아키텍처");
        assertThat(book.title()).isEqualTo(TITLE);
    }

    @Test
    @DisplayName("필수 값이 없으면 해당 필수 값 예외가 발생한다")
    void required() {
        // given
        final BookId id = new BookId("book-1");

        // when & then
        assertThatThrownBy(() -> Book.restore(null, ISBN, TITLE))
                .isInstanceOf(BookIdRequiredException.class)
                .hasMessage("도서 ID는 필수입니다.");
        assertThatThrownBy(() -> Book.restore(id, null, TITLE))
                .isInstanceOf(IsbnRequiredException.class)
                .hasMessage("ISBN은 필수입니다.");
        assertThatThrownBy(() -> Book.restore(id, ISBN, null))
                .isInstanceOf(BookTitleRequiredException.class)
                .hasMessage("도서 제목은 필수입니다.");
    }

}
