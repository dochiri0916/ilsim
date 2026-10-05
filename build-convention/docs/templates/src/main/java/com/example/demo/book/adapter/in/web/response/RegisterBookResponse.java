package com.example.demo.book.adapter.in.web.response;

import com.example.demo.book.application.port.in.result.RegisterBookResult;

public record RegisterBookResponse(String bookId, String isbn, String title) {

    public static RegisterBookResponse from(final RegisterBookResult result) {
        return new RegisterBookResponse(result.bookId(), result.isbn(), result.title());
    }

}
