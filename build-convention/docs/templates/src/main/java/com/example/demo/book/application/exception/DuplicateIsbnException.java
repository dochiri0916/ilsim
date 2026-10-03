package com.example.demo.book.application.exception;

public final class DuplicateIsbnException extends BookApplicationException {

    public DuplicateIsbnException() {
        super("이미 등록된 ISBN입니다.");
    }

}
