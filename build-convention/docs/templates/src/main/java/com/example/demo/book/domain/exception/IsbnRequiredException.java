package com.example.demo.book.domain.exception;

public final class IsbnRequiredException extends BookDomainException {

    public IsbnRequiredException() {
        super("ISBN은 필수입니다.");
    }

}
