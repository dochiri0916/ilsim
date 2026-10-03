package com.example.demo.book.domain.event;

import com.example.demo.book.domain.exception.BookIdRequiredException;
import com.example.demo.book.domain.exception.IsbnRequiredException;
import com.example.demo.book.domain.vo.BookId;
import com.example.demo.book.domain.vo.Isbn;

public record BookRegistered(BookId bookId, Isbn isbn) {

    public BookRegistered {
        if (bookId == null) {
            throw new BookIdRequiredException();
        }
        if (isbn == null) {
            throw new IsbnRequiredException();
        }
    }

}
