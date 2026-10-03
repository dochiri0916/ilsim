package com.example.demo.global.persistence;

import com.example.demo.global.application.exception.RepositoryOperationException;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
