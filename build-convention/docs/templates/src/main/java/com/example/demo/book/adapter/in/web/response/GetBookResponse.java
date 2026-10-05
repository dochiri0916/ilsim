package com.example.demo.book.adapter.in.web.response;

import com.example.demo.book.application.port.in.result.GetBookResult;

public record GetBookResponse(String bookId, String isbn, String title) {

    public static GetBookResponse from(final GetBookResult result) {
        return new GetBookResponse(result.bookId(), result.isbn(), result.title());
    }

}
