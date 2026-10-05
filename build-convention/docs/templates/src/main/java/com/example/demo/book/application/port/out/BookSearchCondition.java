package com.example.demo.book.application.port.out;

import com.example.demo.book.application.exception.InvalidQueryException;
import com.example.demo.book.domain.vo.Isbn;

public record BookSearchCondition(Isbn isbn, int page, int size) {

    public BookSearchCondition {
        if (page < 0) {
            throw new InvalidQueryException("페이지는 0 이상이어야 합니다.");
        }
        if (size < 1 || size > 100) {
            throw new InvalidQueryException("페이지 크기는 1부터 100까지여야 합니다.");
        }
    }

    public long offset() {
        return (long) page * size;
    }

}
