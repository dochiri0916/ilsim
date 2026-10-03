package com.example.demo.book.application.exception;

public abstract sealed class BookApplicationException extends RuntimeException
        permits DuplicateIsbnException,
        BookNotFoundException,
        InvalidCommandException {

    protected BookApplicationException(final String message) {
        super(message);
    }

}
