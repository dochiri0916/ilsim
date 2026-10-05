package com.example.demo.book.application.exception;

public abstract sealed class BookApplicationException extends RuntimeException
        permits DuplicateIsbnException,
        BookNotFoundException,
        InvalidCommandException,
        InvalidQueryException {

    protected BookApplicationException(final String message) {
        super(message);
    }

    protected BookApplicationException(final String message, final Throwable cause) {
        super(message, cause);
    }

}
