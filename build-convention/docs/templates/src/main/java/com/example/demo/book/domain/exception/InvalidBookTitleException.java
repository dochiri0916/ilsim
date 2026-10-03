package com.example.demo.book.domain.exception;

public final class InvalidBookTitleException extends BookDomainException {

    public InvalidBookTitleException() {
        super("도서 제목은 1자 이상 100자 이하여야 합니다.");
    }

}
