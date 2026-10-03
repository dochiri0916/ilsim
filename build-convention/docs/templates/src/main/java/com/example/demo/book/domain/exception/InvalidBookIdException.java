package com.example.demo.book.domain.exception;

public final class InvalidBookIdException extends BookDomainException {

    public InvalidBookIdException() {
        super("유효하지 않은 도서 ID입니다.");
    }

}
