package com.example.demo.book.application.exception;

public final class InvalidCommandException extends BookApplicationException {

    public InvalidCommandException(final String detail) {
        super(detail);
    }

}
