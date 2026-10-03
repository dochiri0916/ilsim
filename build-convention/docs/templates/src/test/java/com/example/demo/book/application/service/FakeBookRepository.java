package com.example.demo.book.application.service;

import com.example.demo.book.application.port.out.BookRepositoryPort;
import com.example.demo.book.domain.Book;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

final class FakeBookRepository implements BookRepositoryPort {

    private final Map<String, Book> books = new LinkedHashMap<>();

    @Override
    public Book create(final Book book) {
        books.put(book.id().value(), book);
        return book;
    }

    @Override
    public Optional<Book> findByBookId(final String bookId) {
        return Optional.ofNullable(books.get(bookId));
    }

    @Override
    public boolean existsByIsbn(final String isbn) {
        return books.values().stream().anyMatch(book -> book.isbn().value().equals(isbn));
    }

    int size() {
        return books.size();
    }

}
