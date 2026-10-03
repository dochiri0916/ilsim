package com.example.demo.global.exception;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

@Component
@RequiredArgsConstructor
public class ProblemDetails {

    private final Clock clock;

    public ProblemDetail create(final HttpStatus status, final String code, final String detail) {
        final ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
        problemDetail.setTitle(status.getReasonPhrase());
        problemDetail.setProperty("code", code);
        problemDetail.setProperty("timestamp", Instant.now(clock));
        return problemDetail;
    }

}
