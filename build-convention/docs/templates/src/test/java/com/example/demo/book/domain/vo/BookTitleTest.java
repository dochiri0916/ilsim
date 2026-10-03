package com.example.demo.book.domain.vo;

import com.example.demo.book.domain.exception.BookTitleRequiredException;
import com.example.demo.book.domain.exception.InvalidBookTitleException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookTitleTest {

    @Test
    @DisplayName("1자와 100자 제목은 앞뒤 공백을 지우고 만든다")
    void boundary() {
        // given
        final String longest = "가".repeat(100);

        // when
        final BookTitle shortest = new BookTitle(" 가 ");
        final BookTitle longTitle = new BookTitle(longest);

        // then
        assertThat(shortest.value()).isEqualTo("가");
        assertThat(longTitle.value()).isEqualTo(longest);
    }

    @Test
    @DisplayName("값이 없거나 비어 있거나 100자를 넘으면 예외가 발생한다")
    void invalid() {
        // given
        final String tooLong = "가".repeat(101);

        // when & then
        assertThatThrownBy(() -> new BookTitle(null))
                .isInstanceOf(BookTitleRequiredException.class)
                .hasMessage("도서 제목은 필수입니다.");
        assertThatThrownBy(() -> new BookTitle(" "))
                .isInstanceOf(InvalidBookTitleException.class)
                .hasMessage("도서 제목은 1자 이상 100자 이하여야 합니다.");
        assertThatThrownBy(() -> new BookTitle(tooLong))
                .isInstanceOf(InvalidBookTitleException.class);
    }

}
