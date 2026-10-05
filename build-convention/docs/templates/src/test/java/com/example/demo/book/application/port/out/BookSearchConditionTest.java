package com.example.demo.book.application.port.out;

import com.example.demo.book.application.exception.InvalidQueryException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookSearchConditionTest {

    @Test
    @DisplayName("offset을 곱셈 전부터 long으로 계산한다")
    void calculatesLongOffset() {
        // given
        final BookSearchCondition first = new BookSearchCondition(null, 0, 1);

        // when
        final BookSearchCondition large = new BookSearchCondition(null, Integer.MAX_VALUE, 100);

        // then
        assertThat(first.offset()).isZero();
        assertThat(large.offset()).isEqualTo(214748364700L);
    }

    @Test
    @DisplayName("직접 생성한 검색 조건도 페이지 범위를 검증한다")
    void rejectsInvalidBounds() {
        // given
        final int page = 0;

        // when & then
        assertThatThrownBy(() -> new BookSearchCondition(null, -1, 10))
                .isInstanceOf(InvalidQueryException.class);
        assertThatThrownBy(() -> new BookSearchCondition(null, page, 0))
                .isInstanceOf(InvalidQueryException.class);
        assertThatThrownBy(() -> new BookSearchCondition(null, page, 101))
                .isInstanceOf(InvalidQueryException.class);
    }

}
