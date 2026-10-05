package com.example.demo.book.application.port.in.usecase;

import com.example.demo.book.application.port.in.query.SearchBooksQuery;
import com.example.demo.book.application.port.in.result.SearchBooksResult;

@FunctionalInterface
public interface SearchBooksUseCase {

    SearchBooksResult search(SearchBooksQuery query);

}
