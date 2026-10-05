package com.example.demo.book.domain.vo;

import com.example.demo.book.domain.exception.BookTitleRequiredException;
import com.example.demo.book.domain.exception.InvalidBookTitleException;

public final class BookTitle {

    private static final int MAX_LENGTH = 100;

    private final String value;

    private BookTitle(final String value) {
        if (value == null) {
            throw new BookTitleRequiredException();
        }
        final String normalized = value.strip();
        if (normalized.isBlank()) {
            throw new InvalidBookTitleException();
        }
        if (normalized.length() > MAX_LENGTH) {
            throw new InvalidBookTitleException();
        }
        this.value = normalized;
    }

    public static BookTitle of(final String value) {
        return new BookTitle(value);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(final Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof final BookTitle other)) {
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
