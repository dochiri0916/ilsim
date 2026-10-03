package com.example.demo.book.application.service;

import com.example.demo.book.application.exception.DuplicateIsbnException;
import com.example.demo.book.application.port.in.RegisterBookCommand;
import com.example.demo.book.application.port.in.RegisterBookResult;
import com.example.demo.book.application.port.in.RegisterBookUseCase;
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
        final Isbn isbn = new Isbn(command.isbn());
        if (bookRepositoryPort.existsByIsbn(isbn.value())) {
            throw new DuplicateIsbnException();
        }
        final Book saved = bookRepositoryPort.create(Book.create(isbn, new BookTitle(command.title())));
        bookEventPort.publish(new BookRegistered(saved.id(), saved.isbn()));
        return new RegisterBookResult(saved.id().value(), saved.isbn().value(), saved.title().value());
    }

}
