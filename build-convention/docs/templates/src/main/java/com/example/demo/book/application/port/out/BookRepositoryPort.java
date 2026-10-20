package com.example.demo.book.application.port.out;

import com.example.demo.book.domain.Book;
import com.example.demo.book.domain.vo.BookId;
import com.example.demo.book.domain.vo.Isbn;

import java.util.Optional;

public interface BookRepositoryPort {

    Book create(Book book);

    Optional<Book> findByBookId(BookId bookId);

    BookSearchPage search(BookSearchCondition condition);

    boolean existsByIsbn(Isbn isbn);

}
