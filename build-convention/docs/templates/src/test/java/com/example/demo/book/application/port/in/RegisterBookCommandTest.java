package com.example.demo.book.application.port.in;

import com.example.demo.book.application.exception.InvalidCommandException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegisterBookCommandTest {

    @Test
    @DisplayName("ISBN과 제목이 있으면 명령을 만든다")
    void create() {
        // given
        final String isbn = "9788966260959";

        // when
        final RegisterBookCommand command = new RegisterBookCommand(isbn, "클린 코드");

        // then
        assertThat(command.isbn()).isEqualTo(isbn);
        assertThat(command.title()).isEqualTo("클린 코드");
    }

    @Test
    @DisplayName("ISBN이나 제목이 없거나 비어 있으면 입력 예외가 발생한다")
    void invalid() {
        // given
        final String isbn = "9788966260959";

        // when & then
        assertThatThrownBy(() -> new RegisterBookCommand(null, "클린 코드"))
                .isInstanceOf(InvalidCommandException.class)
                .hasMessage("ISBN은 빈 값일 수 없습니다.");
        assertThatThrownBy(() -> new RegisterBookCommand(" ", "클린 코드"))
                .isInstanceOf(InvalidCommandException.class);
        assertThatThrownBy(() -> new RegisterBookCommand(isbn, null))
                .isInstanceOf(InvalidCommandException.class)
                .hasMessage("도서 제목은 빈 값일 수 없습니다.");
        assertThatThrownBy(() -> new RegisterBookCommand(isbn, " "))
                .isInstanceOf(InvalidCommandException.class);
    }

}
