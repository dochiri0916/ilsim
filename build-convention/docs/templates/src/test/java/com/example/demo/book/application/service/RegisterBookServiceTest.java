package com.example.demo.book.application.service;

import com.example.demo.book.domain.vo.BookId;

import com.example.demo.book.application.exception.DuplicateIsbnException;
import com.example.demo.book.application.port.in.command.RegisterBookCommand;
import com.example.demo.book.application.port.in.result.RegisterBookResult;
import com.example.demo.book.domain.event.BookRegistered;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegisterBookServiceTest {

    private final FakeBookRepository repository = new FakeBookRepository();
    private final List<BookRegistered> events = new ArrayList<>();
    private final RegisterBookService service = new RegisterBookService(repository, events::add);

    @Test
    @DisplayName("새 ISBN이면 도서를 저장하고 등록 이벤트를 발행한다")
    void register() {
        final RegisterBookCommand command = new RegisterBookCommand(" 9788966260959 ", "클린 코드");

        final RegisterBookResult result = service.register(command);

        assertThat(result.isbn()).isEqualTo("9788966260959");
        assertThat(result.title()).isEqualTo("클린 코드");
        assertThat(repository.findByBookId(BookId.parse(result.bookId()))).isPresent();
        assertThat(events).singleElement()
                .satisfies(event -> assertThat(event.bookId().value()).isEqualTo(result.bookId()));
    }

    @Test
    @DisplayName("이미 등록된 ISBN이면 저장하지 않고 중복 예외가 발생한다")
    void duplicateIsbn() {
        service.register(new RegisterBookCommand("9788966260959", "클린 코드"));

        assertThatThrownBy(() -> service.register(new RegisterBookCommand("9788966260959", "다른 제목")))
                .isInstanceOf(DuplicateIsbnException.class)
                .hasMessage("이미 등록된 ISBN입니다.");
        assertThat(repository.size()).isEqualTo(1);
        assertThat(events).hasSize(1);
    }

}
