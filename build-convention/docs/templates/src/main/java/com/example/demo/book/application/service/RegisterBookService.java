package com.example.demo.book.application.service;

import com.example.demo.book.application.BookResultMapper;
import com.example.demo.book.application.exception.DuplicateIsbnException;
import com.example.demo.book.application.port.in.command.RegisterBookCommand;
import com.example.demo.book.application.port.in.result.RegisterBookResult;
import com.example.demo.book.application.port.in.usecase.RegisterBookUseCase;
import com.example.demo.book.application.port.out.BookEventPort;
import com.example.demo.book.application.port.out.BookRepositoryPort;
import com.example.demo.book.domain.Book;
import com.example.demo.book.domain.event.BookRegistered;
import com.example.demo.book.domain.vo.BookTitle;
import com.example.demo.book.domain.vo.Isbn;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public final class RegisterBookService implements RegisterBookUseCase {

    private final BookRepositoryPort bookRepositoryPort;
    private final BookEventPort bookEventPort;

    @Override
    @Transactional
    public RegisterBookResult register(final RegisterBookCommand command) {
        final String isbnValue = command.isbn();
        final Isbn isbn = Isbn.of(isbnValue);
        if (bookRepositoryPort.existsByIsbn(isbn)) {
            throw new DuplicateIsbnException();
        }
        final String titleValue = command.title();
        final BookTitle title = BookTitle.of(titleValue);
        final Book created = Book.create(isbn, title);
        final Book saved = bookRepositoryPort.create(created);
        final BookRegistered event = new BookRegistered(
                saved.id(),
                saved.isbn());
        bookEventPort.publish(event);
        return BookResultMapper.toRegisterResult(saved);
    }

}
