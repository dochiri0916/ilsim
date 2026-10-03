package com.example.demo.book.application.port.in;

@FunctionalInterface
public interface GetBookUseCase {

    GetBookResult getBook(String bookId);

}
