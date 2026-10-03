package com.example.demo.book.application.port.in;

import com.example.demo.book.application.exception.InvalidCommandException;

public record RegisterBookCommand(String isbn, String title) {

    public RegisterBookCommand {
        if (isbn == null || isbn.isBlank()) {
            throw new InvalidCommandException("ISBN은 빈 값일 수 없습니다.");
        }
        if (title == null || title.isBlank()) {
            throw new InvalidCommandException("도서 제목은 빈 값일 수 없습니다.");
        }
    }

}
