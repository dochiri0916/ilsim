package com.example.demo.book.application;

import com.example.demo.book.application.port.in.result.GetBookResult;
import com.example.demo.book.application.port.in.result.RegisterBookResult;
import com.example.demo.book.application.port.out.BookSearchItem;
import com.example.demo.book.application.port.out.BookSearchItems;
import com.example.demo.book.application.port.out.BookSearchPage;
import com.example.demo.book.application.port.in.result.SearchBookResultItem;
import com.example.demo.book.application.port.in.result.SearchBookResultItems;
import com.example.demo.book.application.port.in.result.SearchBooksResult;
import java.util.List;

import com.example.demo.book.domain.Book;
import com.example.demo.book.domain.vo.BookId;
import com.example.demo.book.domain.vo.BookTitle;
import com.example.demo.book.domain.vo.Isbn;

public final class BookResultMapper {

    private BookResultMapper() {
    }

    public static GetBookResult toGetResult(final Book book) {
        final BookId bookId = book.id();
        final Isbn isbn = book.isbn();
        final BookTitle title = book.title();
        return new GetBookResult(
                bookId.value(),
                isbn.value(),
                title.value());
    }

    public static RegisterBookResult toRegisterResult(final Book book) {
        final BookId bookId = book.id();
        final Isbn isbn = book.isbn();
        final BookTitle title = book.title();
        return new RegisterBookResult(
                bookId.value(),
                isbn.value(),
                title.value());
    }

    public static SearchBooksResult toSearchResult(final BookSearchPage found, final int page, final int size) {
        final BookSearchItems wrapped = found.items();
        final List<BookSearchItem> values = wrapped.values();
        final List<SearchBookResultItem> items = values.stream()
                .map(BookResultMapper::toSearchItem)
                .toList();
        final long total = found.total();
        return new SearchBooksResult(new SearchBookResultItems(items), total, page, size);
    }

    private static SearchBookResultItem toSearchItem(final BookSearchItem item) {
        return new SearchBookResultItem(
                item.bookId(),
                item.isbn(),
                item.title());
    }

}
