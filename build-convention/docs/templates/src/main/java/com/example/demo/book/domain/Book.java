package com.example.demo.book.domain;

import com.example.demo.book.domain.exception.BookIdRequiredException;
import com.example.demo.book.domain.exception.BookTitleRequiredException;
import com.example.demo.book.domain.exception.IsbnRequiredException;
import com.example.demo.book.domain.vo.BookId;
import com.example.demo.book.domain.vo.BookTitle;
import com.example.demo.book.domain.vo.Isbn;

public final class Book {

    private final BookId id;
    private final Isbn isbn;
    private final BookTitle title;

    private Book(final BookId id, final Isbn isbn, final BookTitle title) {
        this.id = validated(id, new BookIdRequiredException());
        this.isbn = validated(isbn, new IsbnRequiredException());
        this.title = validated(title, new BookTitleRequiredException());
    }

    public static Book create(final Isbn isbn, final BookTitle title) {
        return new Book(BookId.generate(), isbn, title);
    }

    public static Book restore(final BookId id, final Isbn isbn, final BookTitle title) {
        return new Book(id, isbn, title);
    }

    public Book rename(final BookTitle newTitle) {
        return new Book(id, isbn, newTitle);
    }

    public BookId id() {
        return id;
    }

    public Isbn isbn() {
        return isbn;
    }

    public BookTitle title() {
        return title;
    }

    private static <T> T validated(final T value, final RuntimeException exception) {
        if (value == null) {
            throw exception;
        }
        return value;
    }

    @Override
    public boolean equals(final Object other) {
        return this == other || other instanceof Book book && id.equals(book.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

}
