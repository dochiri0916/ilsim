package com.example.demo.book.application.service;

import com.example.demo.book.application.exception.BookNotFoundException;
import com.example.demo.book.application.port.in.GetBookResult;
import com.example.demo.book.domain.Book;
import com.example.demo.book.domain.vo.BookTitle;
import com.example.demo.book.domain.vo.Isbn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GetBookServiceTest {

    private final FakeBookRepository repository = new FakeBookRepository();
    private final GetBookService service = new GetBookService(repository);

    @Test
    @DisplayName("저장된 도서를 ID로 조회한다")
    void findsBook() {
        // given
        final Book book = repository.create(Book.create(new Isbn("9788966260959"), new BookTitle("클린 코드")));

        // when
        final GetBookResult result = service.getBook(book.id().value());

        // then
        assertThat(result.bookId()).isEqualTo(book.id().value());
        assertThat(result.isbn()).isEqualTo("9788966260959");
        assertThat(result.title()).isEqualTo("클린 코드");
    }

    @Test
    @DisplayName("없는 도서를 조회하면 예외가 발생한다")
    void notFound() {
        // given
        final String bookId = "missing";

        // when & then
        assertThatThrownBy(() -> service.getBook(bookId))
                .isInstanceOf(BookNotFoundException.class)
                .hasMessage("도서를 찾을 수 없습니다.");
    }

}
