package com.example.demo.book.application.port.in.query;

import com.example.demo.book.application.exception.InvalidQueryException;

public record GetBookQuery(String bookId) {

    public GetBookQuery {
        if (bookId == null || bookId.isBlank()) {
            throw new InvalidQueryException("도서 ID는 필수입니다.");
        }
    }

}
