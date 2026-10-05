package com.example.demo.book.adapter.in.web.response;

import com.example.demo.book.application.port.in.result.SearchBookResultItem;
import com.example.demo.book.application.port.in.result.SearchBookResultItems;
import com.example.demo.book.application.port.in.result.SearchBooksResult;

import java.util.List;

public record SearchBooksResponse(List<SearchBookResponse> items, long total, int page, int size) {

    public SearchBooksResponse {
        items = List.copyOf(items);
    }

    public static SearchBooksResponse from(final SearchBooksResult result) {
        final SearchBookResultItems wrapped = result.items();
        final List<SearchBookResultItem> values = wrapped.values();
        final List<SearchBookResponse> items = values.stream()
                .map(SearchBooksResponse::toItemResponse)
                .toList();
        return new SearchBooksResponse(
                items,
                result.total(),
                result.page(),
                result.size());
    }

    private static SearchBookResponse toItemResponse(final SearchBookResultItem item) {
        return new SearchBookResponse(
                item.bookId(),
                item.isbn(),
                item.title());
    }

}
