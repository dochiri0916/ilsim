package com.example.demo.book.adapter.out.persistence;

import com.example.demo.book.application.port.out.BookSearchCondition;
import com.example.demo.book.application.port.out.BookSearchPage;
import com.example.demo.global.application.exception.RepositoryOperationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BookPersistenceAdapterSearchTest {

    private final BookJpaRepository jpaRepository = mock(BookJpaRepository.class);
    private final BookQueryRepository queryRepository = mock(BookQueryRepository.class);
    private final BookPersistenceAdapter adapter = new BookPersistenceAdapter(jpaRepository, queryRepository);

    @Test
    @DisplayName("내부 Row를 기술 독립 DTO와 일급 컬렉션으로 변환한다")
    void convertsInternalRowsToPortContract() {
        // given
        final BookSearchCondition condition = new BookSearchCondition(null, 0, 10);
        final BookSearchRow row = new BookSearchRow("01900000-0000-7000-8000-000000000001", "9788966260959", "클린 코드");
        when(queryRepository.search(condition)).thenReturn(List.of(row));
        when(queryRepository.count(condition)).thenReturn(21L);

        // when
        final BookSearchPage found = adapter.search(condition);

        // then
        assertThat(found.total()).isEqualTo(21);
        assertThat(found.items().values()).hasSize(1);
        assertThat(found.items().values().getFirst().title()).isEqualTo("클린 코드");
        assertThatThrownBy(() -> found.items().values().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("목록 쿼리 실패는 원인 보존 저장소 예외로 번역한다")
    void translatesListFailure() {
        // given
        final BookSearchCondition condition = new BookSearchCondition(null, 0, 10);
        final DataAccessResourceFailureException failure = new DataAccessResourceFailureException("목록 조회 실패");
        when(queryRepository.search(condition)).thenThrow(failure);

        // when & then
        assertThatThrownBy(() -> adapter.search(condition))
                .isInstanceOf(RepositoryOperationException.class)
                .hasCause(failure);
    }

    @Test
    @DisplayName("count 쿼리도 같은 예외 번역 경계로 감싼다")
    void translatesCountFailure() {
        // given
        final BookSearchCondition condition = new BookSearchCondition(null, 0, 10);
        final DataAccessResourceFailureException failure = new DataAccessResourceFailureException("건수 조회 실패");
        when(queryRepository.search(condition)).thenReturn(List.of());
        when(queryRepository.count(condition)).thenThrow(failure);

        // when & then
        assertThatThrownBy(() -> adapter.search(condition))
                .isInstanceOf(RepositoryOperationException.class)
                .hasCause(failure);
    }

}
