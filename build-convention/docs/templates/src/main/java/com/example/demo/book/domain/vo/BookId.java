package com.example.demo.book.domain.vo;

import com.example.demo.book.domain.exception.BookIdRequiredException;
import com.example.demo.book.domain.exception.InvalidBookIdException;
import com.github.f4b6a3.uuid.UuidCreator;

import java.util.UUID;

public final class BookId {

    private final UUID value;

    private BookId(final UUID value) {
        if (value == null) {
            throw new BookIdRequiredException();
        }
        if (value.version() != 7) {
            throw new InvalidBookIdException();
        }
        this.value = value;
    }

    public static BookId of(final UUID value) {
        return new BookId(value);
    }

    public static BookId generate() {
        return of(UuidCreator.getTimeOrderedEpoch());
    }

    public static BookId parse(final String value) {
        if (value == null) {
            throw new BookIdRequiredException();
        }
        if (!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new InvalidBookIdException();
        }
        return of(UUID.fromString(value));
    }

    public String value() {
        return value.toString();
    }

    @Override
    public boolean equals(final Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof final BookId other)) {
            return false;
        }
        final UUID otherValue = other.value;
        return value.equals(otherValue);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

}
