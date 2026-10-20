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
        final BookId bookId = BookId.parse("01900000-0000-7000-8000-000000000001");

        final BookRegistered event = new BookRegistered(bookId, Isbn.of("9788966260959"));

        assertThat(event.bookId()).isEqualTo(bookId);
        assertThat(event.isbn().value()).isEqualTo("9788966260959");
    }

    @Test
    @DisplayName("도서 ID나 ISBN이 없으면 예외가 발생한다")
    void required() {
        final Isbn isbn = Isbn.of("9788966260959");

        assertThatThrownBy(() -> new BookRegistered(null, isbn)).isInstanceOf(BookIdRequiredException.class);
        assertThatThrownBy(() -> new BookRegistered(BookId.parse("01900000-0000-7000-8000-000000000001"), null)).isInstanceOf(IsbnRequiredException.class);
    }

}
