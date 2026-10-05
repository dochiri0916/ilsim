package com.example.demo.book.domain.vo;

import com.example.demo.book.domain.exception.InvalidIsbnException;
import com.example.demo.book.domain.exception.IsbnRequiredException;

import java.util.regex.Pattern;

public final class Isbn {

    private static final Pattern ISBN_PATTERN = Pattern.compile("^\\d{13}$");

    private final String value;

    private Isbn(final String value) {
        if (value == null) {
            throw new IsbnRequiredException();
        }
        final String normalized = value.strip();
        if (normalized.isBlank()) {
            throw new IsbnRequiredException();
        }
        final boolean validFormat = ISBN_PATTERN.matcher(normalized)
                .matches();
        if (!validFormat) {
            throw new InvalidIsbnException();
        }
        this.value = normalized;
    }

    public static Isbn of(final String value) {
        return new Isbn(value);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(final Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof final Isbn other)) {
            return false;
        }
        final String otherValue = other.value;
        return value.equals(otherValue);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

}
