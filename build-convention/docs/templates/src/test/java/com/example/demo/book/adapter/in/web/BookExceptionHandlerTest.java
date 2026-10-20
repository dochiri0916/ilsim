package com.example.demo.book.adapter.in.web;

import com.example.demo.book.application.exception.BookApplicationException;
import com.example.demo.book.application.exception.BookNotFoundException;
import com.example.demo.book.application.exception.DuplicateIsbnException;
import com.example.demo.book.application.exception.InvalidCommandException;
import com.example.demo.book.application.exception.InvalidQueryException;
import com.example.demo.book.domain.exception.BookDomainException;
import com.example.demo.book.domain.exception.BookIdRequiredException;
import com.example.demo.book.domain.exception.BookTitleRequiredException;
import com.example.demo.book.domain.exception.InvalidBookIdException;
import com.example.demo.book.domain.exception.InvalidBookTitleException;
import com.example.demo.book.domain.exception.InvalidIsbnException;
import com.example.demo.book.domain.exception.IsbnRequiredException;
import com.example.demo.global.exception.ProblemDetails;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BookExceptionHandlerTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private final BookExceptionHandler handler = new BookExceptionHandler(
            new ProblemDetails(Clock.fixed(NOW, ZoneOffset.UTC)));

    @Test
    @DisplayName("도메인 예외는 모두 400과 예외별 코드, 예외 메시지로 바뀐다")
    void domainExceptions() {
        final Map<BookDomainException, String> codes = Map.of(
                new BookIdRequiredException(), "BOOK_ID_REQUIRED",
                new InvalidBookIdException(), "BOOK_ID_INVALID",
                new IsbnRequiredException(), "ISBN_REQUIRED",
                new InvalidIsbnException(), "ISBN_INVALID",
                new BookTitleRequiredException(), "BOOK_TITLE_REQUIRED",
                new InvalidBookTitleException(), "BOOK_TITLE_INVALID");

        codes.forEach((exception, code) -> {
            final ProblemDetail problem = handler.handle(exception);

            assertThat(problem.getStatus()).isEqualTo(400);
            assertThat(problem.getProperties()).containsEntry("code", code).containsEntry("timestamp", NOW);
            assertThat(problem.getDetail()).isEqualTo(exception.getMessage());
        });
    }

    @Test
    @DisplayName("애플리케이션 예외는 예외별 상태와 코드로 바뀐다")
    void applicationExceptions() {
        final Map<BookApplicationException, Integer> statuses = Map.of(
                new DuplicateIsbnException(), 409,
                new BookNotFoundException(), 404,
                new InvalidCommandException("입력이 잘못되었습니다."), 400,
                new InvalidQueryException("조회 입력이 잘못되었습니다."), 400);

        statuses.forEach((exception, status) -> {
            final ProblemDetail problem = handler.handle(exception);

            assertThat(problem.getStatus()).isEqualTo(status);
            assertThat(problem.getDetail()).isEqualTo(exception.getMessage());
        });
    }

}
