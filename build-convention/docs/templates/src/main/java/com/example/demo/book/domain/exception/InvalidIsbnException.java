package com.example.demo.book.domain.exception;

public final class InvalidIsbnException extends BookDomainException {

    public InvalidIsbnException() {
        super("ISBN은 숫자 13자리여야 합니다.");
    }

}
