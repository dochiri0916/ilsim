package com.example.demo.book.domain.vo;

import com.example.demo.book.domain.exception.BookIdRequiredException;
import com.example.demo.book.domain.exception.InvalidBookIdException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookIdTest {

    @Test
    @DisplayName("앞뒤 공백을 지운 값으로 만들고 generate는 32자 ID를 만든다")
    void create() {
        // given
        final String value = " book-1 ";

        // when
        final BookId id = new BookId(value);

        // then
        assertThat(id.value()).isEqualTo("book-1");
        assertThat(BookId.generate().value()).hasSize(32).doesNotContain("-");
    }

    @Test
    @DisplayName("값이 없거나 비어 있으면 예외가 발생한다")
    void invalid() {
        // given
        final String blank = "  ";

        // when & then
        assertThatThrownBy(() -> new BookId(null))
                .isInstanceOf(BookIdRequiredException.class)
                .hasMessage("도서 ID는 필수입니다.");
        assertThatThrownBy(() -> new BookId(blank))
                .isInstanceOf(InvalidBookIdException.class)
                .hasMessage("유효하지 않은 도서 ID입니다.");
    }

}
