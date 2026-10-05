package com.example.demo.book.application.service;

import com.example.demo.book.application.BookResultMapper;
import com.example.demo.book.application.port.in.query.GetBookQuery;
import com.example.demo.book.application.exception.BookNotFoundException;
import com.example.demo.book.application.port.in.result.GetBookResult;
import com.example.demo.book.application.port.in.usecase.GetBookUseCase;
import com.example.demo.book.application.port.out.BookRepositoryPort;
import com.example.demo.book.domain.Book;
import com.example.demo.book.domain.vo.BookId;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public final class GetBookService implements GetBookUseCase {

    private final BookRepositoryPort bookRepositoryPort;

    @Override
    @Transactional(readOnly = true)
    public GetBookResult getBook(final GetBookQuery query) {
        final String publicId = query.bookId();
        final BookId bookId = BookId.parse(publicId);
        final Optional<Book> found = bookRepositoryPort.findByBookId(bookId);
        final Book book = found.orElseThrow(BookNotFoundException::new);
        return BookResultMapper.toGetResult(book);
    }

}
