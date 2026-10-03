package com.example.demo.book.application.exception;

public final class BookNotFoundException extends BookApplicationException {

    public BookNotFoundException() {
        super("도서를 찾을 수 없습니다.");
    }

}
