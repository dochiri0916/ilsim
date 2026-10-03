package com.example.demo.book.domain.exception;

public abstract sealed class BookDomainException extends RuntimeException
        permits BookIdRequiredException,
        InvalidBookIdException,
        IsbnRequiredException,
        InvalidIsbnException,
        BookTitleRequiredException,
        InvalidBookTitleException {

    protected BookDomainException(final String message) {
        super(message);
    }

}
