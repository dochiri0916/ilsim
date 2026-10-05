package com.example.demo.book.domain.vo;

import com.example.demo.book.domain.vo.BookId;

import com.example.demo.book.domain.exception.BookIdRequiredException;
import com.example.demo.book.domain.exception.InvalidBookIdException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookIdTest {

    @Test
    @DisplayName("UUID v7을 파싱하고 generate는 UUID v7 식별자를 만든다")
    void create() {
        // given
        final String value = "01900000-0000-7000-8000-000000000001";

        // when
        final BookId id = BookId.parse(value);

        // then
        assertThat(id.value()).isEqualTo("01900000-0000-7000-8000-000000000001");
        final String generated = BookId.generate().value();
        assertThat(generated).hasSize(36).matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(BookId.parse(value)).isEqualTo(id).hasSameHashCodeAs(id);
    }

    @Test
    @DisplayName("값이 없거나 비어 있으면 예외가 발생한다")
    void invalid() {
        // given
        final String blank = "  ";

        // when & then
        assertThatThrownBy(() -> BookId.parse(null))
                .isInstanceOf(BookIdRequiredException.class)
                .hasMessage("도서 ID는 필수입니다.");
        assertThatThrownBy(() -> BookId.parse("01900000-0000-4000-8000-000000000001"))
                .isInstanceOf(InvalidBookIdException.class)
                .hasMessage("유효하지 않은 도서 ID입니다.");
        assertThatThrownBy(() -> BookId.parse(blank))
                .isInstanceOf(InvalidBookIdException.class)
                .hasMessage("유효하지 않은 도서 ID입니다.");
    }

}
