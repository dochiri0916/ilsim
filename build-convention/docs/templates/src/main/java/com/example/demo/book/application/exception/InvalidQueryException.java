package com.example.demo.book.application.exception;

public final class InvalidQueryException extends BookApplicationException {

    public InvalidQueryException(final String message) {
        super(message);
    }

}
