package com.example.demo.book.domain.exception;

public final class BookIdRequiredException extends BookDomainException {

    public BookIdRequiredException() {
        super("도서 ID는 필수입니다.");
    }

}
