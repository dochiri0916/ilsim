package com.example.demo.book.domain.vo;

import com.example.demo.book.domain.exception.BookIdRequiredException;
import com.example.demo.book.domain.exception.InvalidBookIdException;

import java.util.UUID;

public record BookId(String value) {

    public BookId {
        if (value == null) {
            throw new BookIdRequiredException();
        }
        value = value.strip();
        if (value.isBlank()) {
            throw new InvalidBookIdException();
        }
    }

    public static BookId generate() {
        return new BookId(UUID.randomUUID().toString().replace("-", ""));
    }

}
