package com.example.demo.book.application.port.in.query;

import com.example.demo.book.application.exception.InvalidQueryException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchBooksQueryTest {

    @Test
    @DisplayName("첫 페이지와 최대 페이지 크기를 허용한다")
    void acceptsBounds() {
        final SearchBooksQuery first = new SearchBooksQuery(null, 0, 1);

        final SearchBooksQuery large = new SearchBooksQuery(null, Integer.MAX_VALUE, 100);

        assertThat(first.page()).isZero();
        assertThat(first.size()).isEqualTo(1);
        assertThat(large.size()).isEqualTo(100);
    }

    @Test
    @DisplayName("음수 페이지와 범위 밖 크기를 거부한다")
    void rejectsInvalidBounds() {
        final int page = 0;

        assertThatThrownBy(() -> new SearchBooksQuery(null, -1, 10))
                .isInstanceOf(InvalidQueryException.class);
        assertThatThrownBy(() -> new SearchBooksQuery(null, page, 0))
                .isInstanceOf(InvalidQueryException.class);
        assertThatThrownBy(() -> new SearchBooksQuery(null, page, 101))
                .isInstanceOf(InvalidQueryException.class);
    }

}
