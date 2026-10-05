package com.example.demo.book.application.port.in.result;

public record SearchBooksResult(SearchBookResultItems items, long total, int page, int size) {
}
