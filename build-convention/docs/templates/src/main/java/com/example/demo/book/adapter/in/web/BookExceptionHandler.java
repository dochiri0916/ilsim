package com.example.demo.book.adapter.in.web;

import com.example.demo.book.application.exception.BookApplicationException;
import com.example.demo.book.application.exception.BookNotFoundException;
import com.example.demo.book.application.exception.DuplicateIsbnException;
import com.example.demo.book.application.exception.InvalidCommandException;
import com.example.demo.book.domain.exception.BookDomainException;
import com.example.demo.book.domain.exception.BookIdRequiredException;
import com.example.demo.book.domain.exception.BookTitleRequiredException;
import com.example.demo.book.domain.exception.InvalidBookIdException;
import com.example.demo.book.domain.exception.InvalidBookTitleException;
import com.example.demo.book.domain.exception.InvalidIsbnException;
import com.example.demo.book.domain.exception.IsbnRequiredException;
import com.example.demo.global.exception.ProblemDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RequiredArgsConstructor
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class BookExceptionHandler {

    private final ProblemDetails problemDetails;

    @ExceptionHandler(BookDomainException.class)
    public ProblemDetail handle(final BookDomainException exception) {
        return switch (exception) {
            case BookIdRequiredException e -> respond(e, HttpStatus.BAD_REQUEST, "BOOK_ID_REQUIRED");
            case InvalidBookIdException e -> respond(e, HttpStatus.BAD_REQUEST, "BOOK_ID_INVALID");
            case IsbnRequiredException e -> respond(e, HttpStatus.BAD_REQUEST, "ISBN_REQUIRED");
            case InvalidIsbnException e -> respond(e, HttpStatus.BAD_REQUEST, "ISBN_INVALID");
            case BookTitleRequiredException e -> respond(e, HttpStatus.BAD_REQUEST, "BOOK_TITLE_REQUIRED");
            case InvalidBookTitleException e -> respond(e, HttpStatus.BAD_REQUEST, "BOOK_TITLE_INVALID");
        };
    }

    @ExceptionHandler(BookApplicationException.class)
    public ProblemDetail handle(final BookApplicationException exception) {
        return switch (exception) {
            case DuplicateIsbnException e -> respond(e, HttpStatus.CONFLICT, "DUPLICATE_ISBN");
            case BookNotFoundException e -> respond(e, HttpStatus.NOT_FOUND, "BOOK_NOT_FOUND");
            case InvalidCommandException e -> respond(e, HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        };
    }

    private ProblemDetail respond(final RuntimeException exception, final HttpStatus status, final String code) {
        log.info("도서 예외가 발생했습니다. type={}, code={}", exception.getClass().getSimpleName(), code);
        return problemDetails.create(status, code, exception.getMessage());
    }

}
