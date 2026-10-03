package com.example.demo.book.domain.vo;

import com.example.demo.book.domain.exception.InvalidIsbnException;
import com.example.demo.book.domain.exception.IsbnRequiredException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IsbnTest {

    @Test
    @DisplayName("숫자 13자리면 앞뒤 공백을 지우고 만든다")
    void create() {
        // given
        final String value = " 9788966260959 ";

        // when
        final Isbn isbn = new Isbn(value);

        // then
        assertThat(isbn.value()).isEqualTo("9788966260959");
    }

    @Test
    @DisplayName("값이 없거나 비어 있거나 13자리 숫자가 아니면 예외가 발생한다")
    void invalid() {
        // given
        final String shortValue = "978896626095";

        // when & then
        assertThatThrownBy(() -> new Isbn(null))
                .isInstanceOf(IsbnRequiredException.class)
                .hasMessage("ISBN은 필수입니다.");
        assertThatThrownBy(() -> new Isbn(" "))
                .isInstanceOf(IsbnRequiredException.class);
        assertThatThrownBy(() -> new Isbn(shortValue))
                .isInstanceOf(InvalidIsbnException.class)
                .hasMessage("ISBN은 숫자 13자리여야 합니다.");
        assertThatThrownBy(() -> new Isbn("978896626095X"))
                .isInstanceOf(InvalidIsbnException.class);
    }

}
