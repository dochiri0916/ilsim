package com.example.demo.global.persistence;

import com.example.demo.global.application.exception.RepositoryOperationException;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.hibernate.exception.ConstraintViolationException;

import java.sql.SQLException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class RepositoryExceptionTranslatorTest {

    @Test
    @DisplayName("성공한 작업의 결과는 그대로 돌려준다")
    void success() {
        // given
        final String value = "결과";

        // when
        final String result = RepositoryExceptionTranslator.execute(() -> value);

        // then
        assertThat(result).isEqualTo(value);
    }

    @Test
    @DisplayName("원인 체인의 정확한 유니크 제약 이름만 일치로 판단한다")
    void matches_exact_constraint_name() {
        // given
        final SQLException sql = new SQLException("중복", "23505");
        final ConstraintViolationException violation = new ConstraintViolationException("중복", sql, "uk_books_isbn");
        final DataIntegrityViolationException exception = new DataIntegrityViolationException("저장 실패", violation);

        // when
        final boolean expected = RepositoryExceptionTranslator.isConstraintViolation(exception, "uk_books_isbn");
        final boolean other = RepositoryExceptionTranslator.isConstraintViolation(exception, "uk_books_public_id");

        // then
        assertThat(expected).isTrue();
        assertThat(other).isFalse();
        assertThat(RepositoryExceptionTranslator.isConstraintViolation(new PersistenceException("기타"), "uk_books_isbn"))
                .isFalse();
    }

    @Test
    @DisplayName("원인 체인에 순환이 있어도 제약 확인은 종료한다")
    void handles_cyclic_causes() {
        // given
        final Throwable first = new Throwable("첫 원인");
        final Throwable second = new Throwable("두 번째 원인");
        first.initCause(second);
        second.initCause(first);

        // when
        final boolean result = assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> RepositoryExceptionTranslator.isConstraintViolation(first, "uk_books_isbn"));

        // then
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("저장소 기술 예외는 RepositoryOperationException으로 바뀐다")
    void translate() {
        // given
        final DataAccessResourceFailureException cause = new DataAccessResourceFailureException("연결 실패");

        // when & then
        assertThatThrownBy(() -> RepositoryExceptionTranslator.execute(() -> {
            throw cause;
        }))
                .isInstanceOf(RepositoryOperationException.class)
                .hasMessage("저장소 작업을 처리하지 못했습니다.")
                .hasCause(cause);
        assertThatThrownBy(() -> RepositoryExceptionTranslator.execute(() -> {
            throw new PersistenceException("실패");
        })).isInstanceOf(RepositoryOperationException.class);
    }

}
