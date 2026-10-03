package com.example.demo.global.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalApiControllerAdviceTest {

    @Test
    @DisplayName("처리되지 않은 예외는 내부 정보 없이 500으로 바뀐다")
    void unhandled() {
        // given
        final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        final GlobalApiControllerAdvice advice = new GlobalApiControllerAdvice(new ProblemDetails(clock));

        // when
        final ProblemDetail problem = advice.handleUnhandledException(
                new IllegalStateException("비밀 정보"), new MockHttpServletRequest("GET", "/api/books"));

        // then
        assertThat(problem.getStatus()).isEqualTo(500);
        assertThat(problem.getDetail()).isEqualTo("일시적인 오류가 발생했습니다.");
        assertThat(problem.getProperties())
                .containsEntry("code", "INTERNAL_ERROR")
                .containsEntry("timestamp", Instant.parse("2026-01-01T00:00:00Z"));
    }

}
