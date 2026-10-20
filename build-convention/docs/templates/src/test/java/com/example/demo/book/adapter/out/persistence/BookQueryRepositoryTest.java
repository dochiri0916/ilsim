package com.example.demo.book.adapter.out.persistence;

import com.example.demo.book.application.port.out.BookSearchCondition;
import com.example.demo.book.domain.vo.BookId;
import com.example.demo.book.domain.vo.Isbn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class BookQueryRepositoryTest {

    @Autowired
    private BookQueryRepository queryRepository;

    @Autowired
    private BookJpaRepository jpaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("생성 시각이 같아도 ID 내림차순으로 페이지를 나누고 count는 전체 조건을 센다")
    void usesStablePagesAndSeparateCount() {
        save("9788966260959", "첫 도서");
        save("9780000000000", "둘째 도서");
        save("9780000000001", "셋째 도서");
        jdbcTemplate.update("UPDATE books SET created_at = TIMESTAMP '2026-01-01 00:00:00'");
        final BookSearchCondition firstPage = new BookSearchCondition(null, 0, 1);
        final BookSearchCondition secondPage = new BookSearchCondition(null, 1, 1);
        final BookSearchCondition thirdPage = new BookSearchCondition(null, 2, 1);

        final List<BookSearchRow> first = queryRepository.search(firstPage);
        final List<BookSearchRow> second = queryRepository.search(secondPage);
        final List<BookSearchRow> third = queryRepository.search(thirdPage);

        assertThat(first).extracting(BookSearchRow::title).containsExactly("셋째 도서");
        assertThat(second).extracting(BookSearchRow::title).containsExactly("둘째 도서");
        assertThat(third).extracting(BookSearchRow::title).containsExactly("첫 도서");
        assertThat(queryRepository.count(firstPage)).isEqualTo(3);
        assertThat(queryRepository.count(thirdPage)).isEqualTo(3);
    }

    @Test
    @DisplayName("선택 ISBN 조건을 목록과 count에 동일하게 적용한다")
    void sharesOptionalFilterAcrossListAndCount() {
        save("9788966260959", "클린 코드");
        save("9780000000000", "다른 도서");
        final BookSearchCondition match = new BookSearchCondition(Isbn.of("9788966260959"), 0, 100);
        final BookSearchCondition missing = new BookSearchCondition(Isbn.of("9780000000001"), 0, 100);

        final List<BookSearchRow> rows = queryRepository.search(match);

        assertThat(rows).extracting(BookSearchRow::isbn).containsExactly("9788966260959");
        assertThat(rows).extracting(BookSearchRow::title).containsExactly("클린 코드");
        assertThat(queryRepository.count(match)).isEqualTo(1);
        assertThat(queryRepository.search(missing)).isEmpty();
        assertThat(queryRepository.count(missing)).isZero();
    }

    @Test
    @DisplayName("큰 offset을 실제 DB에 전달해도 오버플로 없이 빈 페이지를 조회한다")
    void supportsLargeOffset() {
        save("9788966260959", "클린 코드");
        final BookSearchCondition condition = new BookSearchCondition(null, Integer.MAX_VALUE, 100);

        final List<BookSearchRow> rows = queryRepository.search(condition);

        assertThat(rows).isEmpty();
        assertThat(queryRepository.count(condition)).isEqualTo(1);
    }

    private void save(final String isbn, final String title) {
        final BookId id = BookId.generate();
        final String value = id.value();
        final BookEntity entity = BookEntity.create(value, isbn, title);
        jpaRepository.saveAndFlush(entity);
    }

}
