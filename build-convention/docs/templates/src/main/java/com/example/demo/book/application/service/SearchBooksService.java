package com.example.demo.book.application.service;

import com.example.demo.book.application.BookResultMapper;
import com.example.demo.book.application.port.in.query.SearchBooksQuery;
import com.example.demo.book.application.port.in.result.SearchBooksResult;
import com.example.demo.book.application.port.in.usecase.SearchBooksUseCase;
import com.example.demo.book.application.port.out.BookRepositoryPort;
import com.example.demo.book.application.port.out.BookSearchCondition;
import com.example.demo.book.application.port.out.BookSearchPage;
import com.example.demo.book.domain.vo.Isbn;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public final class SearchBooksService implements SearchBooksUseCase {

    private final BookRepositoryPort bookRepositoryPort;

    @Override
    @Transactional(readOnly = true)
    public SearchBooksResult search(final SearchBooksQuery query) {
        final String value = query.isbn();
        final Isbn isbn = toIsbn(value);
        final int page = query.page();
        final int size = query.size();
        final BookSearchCondition condition = new BookSearchCondition(isbn, page, size);
        final BookSearchPage found = bookRepositoryPort.search(condition);
        return BookResultMapper.toSearchResult(found, page, size);
    }

    private static Isbn toIsbn(final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Isbn.of(value);
    }

}
