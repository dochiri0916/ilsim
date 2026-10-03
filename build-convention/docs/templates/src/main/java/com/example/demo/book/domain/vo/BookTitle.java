package com.example.demo.book.domain.vo;

import com.example.demo.book.domain.exception.BookTitleRequiredException;
import com.example.demo.book.domain.exception.InvalidBookTitleException;

public record BookTitle(String value) {

    private static final int MAX_LENGTH = 100;

    public BookTitle {
        if (value == null) {
            throw new BookTitleRequiredException();
        }
        value = value.strip();
        if (value.isBlank() || value.length() > MAX_LENGTH) {
            throw new InvalidBookTitleException();
        }
    }

}
