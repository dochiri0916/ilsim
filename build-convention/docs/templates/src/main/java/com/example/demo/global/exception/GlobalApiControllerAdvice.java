package com.example.demo.global.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@Slf4j
@RequiredArgsConstructor
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalApiControllerAdvice extends ResponseEntityExceptionHandler {

    private static final String INTERNAL_ERROR_CODE = "INTERNAL_ERROR";
    private static final String INTERNAL_ERROR_DETAIL = "일시적인 오류가 발생했습니다.";

    private final ProblemDetails problemDetails;

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnhandledException(final Exception exception, final HttpServletRequest request) {
        log.error("미처리 예외가 발생했습니다. uri={}, method={}", request.getRequestURI(), request.getMethod(), exception);
        return problemDetails.create(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR_CODE, INTERNAL_ERROR_DETAIL);
    }

}
