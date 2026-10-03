package com.example.demo.book.application.service;

import com.example.demo.book.application.exception.BookNotFoundException;
import com.example.demo.book.application.port.in.GetBookResult;
import com.example.demo.book.application.port.in.GetBookUseCase;
import com.example.demo.book.application.port.out.BookRepositoryPort;
import com.example.demo.book.domain.Book;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public final class GetBookService implements GetBookUseCase {

    private final BookRepositoryPort bookRepositoryPort;

    @Override
    @Transactional(readOnly = true)
    public GetBookResult getBook(final String bookId) {
        final Book book = bookRepositoryPort.findByBookId(bookId).orElseThrow(BookNotFoundException::new);
        return new GetBookResult(book.id().value(), book.isbn().value(), book.title().value());
    }

}
