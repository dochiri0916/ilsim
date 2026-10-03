package com.example.demo.book.domain.exception;

public final class BookTitleRequiredException extends BookDomainException {

    public BookTitleRequiredException() {
        super("도서 제목은 필수입니다.");
    }

}
