package com.example.demo.book.application.service;

import com.example.demo.book.application.port.in.query.SearchBooksQuery;
import com.example.demo.book.application.port.in.result.SearchBooksResult;
import com.example.demo.book.domain.Book;
import com.example.demo.book.domain.vo.BookTitle;
import com.example.demo.book.domain.vo.Isbn;
import com.example.demo.book.domain.exception.InvalidIsbnException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchBooksServiceTest {

    private final FakeBookRepository repository = new FakeBookRepository();
    private final SearchBooksService service = new SearchBooksService(repository);

    @Test
    @DisplayName("Query의 ISBN을 VO 검색 조건으로 변환하고 ResultMapper로 결과를 조립한다")
    void convertsCriteriaAndResults() {
        repository.create(Book.create(Isbn.of("9788966260959"), BookTitle.of("클린 코드")));
        repository.create(Book.create(Isbn.of("9780000000000"), BookTitle.of("다른 도서")));
        final SearchBooksQuery query = new SearchBooksQuery("9788966260959", 0, 10);

        final SearchBooksResult result = service.search(query);

        assertThat(result.items().values()).hasSize(1);
        assertThat(result.items().values().getFirst().isbn()).isEqualTo("9788966260959");
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.page()).isZero();
        assertThat(result.size()).isEqualTo(10);
    }

    @Test
    @DisplayName("입력 없는 선택 조건은 생략하고 페이지와 전체 건수를 구분한다")
    void omitsEmptyOptionalFilter() {
        repository.create(Book.create(Isbn.of("9788966260959"), BookTitle.of("첫 도서")));
        repository.create(Book.create(Isbn.of("9780000000000"), BookTitle.of("둘째 도서")));

        final SearchBooksResult result = service.search(new SearchBooksQuery(" ", 1, 1));

        assertThat(result.items().values()).hasSize(1);
        assertThat(result.total()).isEqualTo(2);
        assertThat(result.page()).isEqualTo(1);
    }

    @Test
    @DisplayName("잘못된 ISBN은 도메인 VO 검증 예외를 그대로 전달한다")
    void rejectsMalformedIsbn() {
        final SearchBooksQuery query = new SearchBooksQuery("invalid", 0, 10);

        assertThatThrownBy(() -> service.search(query)).isInstanceOf(InvalidIsbnException.class);
    }

}
