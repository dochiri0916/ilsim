package com.example.demo.book.domain.event;

import com.example.demo.book.domain.exception.BookIdRequiredException;
import com.example.demo.book.domain.exception.IsbnRequiredException;
import com.example.demo.book.domain.vo.BookId;
import com.example.demo.book.domain.vo.Isbn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookRegisteredTest {

    @Test
    @DisplayName("도서 ID와 ISBN으로 등록 이벤트를 만든다")
    void create() {
        // given
        final BookId bookId = new BookId("book-1");

        // when
        final BookRegistered event = new BookRegistered(bookId, new Isbn("9788966260959"));

        // then
        assertThat(event.bookId()).isEqualTo(bookId);
        assertThat(event.isbn().value()).isEqualTo("9788966260959");
    }

    @Test
    @DisplayName("도서 ID나 ISBN이 없으면 예외가 발생한다")
    void required() {
        // given
        final Isbn isbn = new Isbn("9788966260959");

        // when & then
        assertThatThrownBy(() -> new BookRegistered(null, isbn)).isInstanceOf(BookIdRequiredException.class);
        assertThatThrownBy(() -> new BookRegistered(new BookId("book-1"), null)).isInstanceOf(IsbnRequiredException.class);
    }

}
