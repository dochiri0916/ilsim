package com.example.demo.book.domain.vo;

import com.example.demo.book.domain.exception.InvalidIsbnException;
import com.example.demo.book.domain.exception.IsbnRequiredException;

import java.util.regex.Pattern;

public record Isbn(String value) {

    private static final Pattern ISBN_PATTERN = Pattern.compile("^\\d{13}$");

    public Isbn {
        if (value == null) {
            throw new IsbnRequiredException();
        }
        value = value.strip();
        if (value.isBlank()) {
            throw new IsbnRequiredException();
        }
        if (!ISBN_PATTERN.matcher(value).matches()) {
            throw new InvalidIsbnException();
        }
    }

}
