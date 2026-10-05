package com.example.demo.book.application.service;

import com.example.demo.book.application.port.out.BookRepositoryPort;
import com.example.demo.book.application.port.out.BookSearchCondition;
import com.example.demo.book.application.port.out.BookSearchItem;
import com.example.demo.book.application.port.out.BookSearchItems;
import com.example.demo.book.application.port.out.BookSearchPage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import com.example.demo.book.domain.Book;
import com.example.demo.book.domain.vo.BookId;
import com.example.demo.book.domain.vo.Isbn;

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
    public Optional<Book> findByBookId(final BookId bookId) {
        return Optional.ofNullable(books.get(bookId.value()));
    }

    @Override
    public boolean existsByIsbn(final Isbn isbn) {
        return books.values().stream().anyMatch(book -> book.isbn().value().equals(isbn.value()));
    }

    int size() {
        return books.size();
    }

    @Override
    public BookSearchPage search(final BookSearchCondition condition) {
        final Isbn isbn = condition.isbn();
        final List<Book> matching = new ArrayList<>(books.values());
        Collections.reverse(matching);
        final List<Book> filtered = matching.stream()
                .filter(book -> isbn == null || book.isbn().equals(isbn))
                .toList();
        final List<BookSearchItem> items = filtered.stream()
                .skip(condition.offset())
                .limit(condition.size())
                .map(FakeBookRepository::toSearchItem)
                .toList();
        return new BookSearchPage(new BookSearchItems(items), filtered.size());
    }

    private static BookSearchItem toSearchItem(final Book book) {
        return new BookSearchItem(book.id().value(), book.isbn().value(), book.title().value());
    }

}
