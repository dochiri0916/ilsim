package com.example.demo.book.application.port.out;

import com.example.demo.book.domain.Book;

import java.util.Optional;

/**
 * 기술적 저장소 작업 실패는 RepositoryOperationException으로 전달한다.
 */
public interface BookRepositoryPort {

    Book create(Book book);

    Optional<Book> findByBookId(String bookId);

    boolean existsByIsbn(String isbn);

}
