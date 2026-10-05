package com.example.demo.book.application.port.in.query;

import com.example.demo.book.application.exception.InvalidQueryException;

public record SearchBooksQuery(String isbn, int page, int size) {

    public SearchBooksQuery {
        if (page < 0) {
            throw new InvalidQueryException("페이지는 0 이상이어야 합니다.");
        }
        if (size < 1 || size > 100) {
            throw new InvalidQueryException("페이지 크기는 1부터 100까지여야 합니다.");
        }
    }

}
