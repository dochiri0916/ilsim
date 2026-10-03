package com.example.demo.book.adapter.out.persistence;

import com.example.demo.book.application.exception.DuplicateIsbnException;
import com.example.demo.book.application.port.out.BookRepositoryPort;
import com.example.demo.book.domain.Book;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

import java.util.Optional;

import static com.example.demo.global.persistence.RepositoryExceptionTranslator.execute;

@Repository
@RequiredArgsConstructor
class BookPersistenceAdapter implements BookRepositoryPort {

    private final BookJpaRepository jpaRepository;

    @Override
    public Book create(final Book book) {
        return execute(() -> {
            try {
                return BookMapper.toDomain(jpaRepository.saveAndFlush(BookMapper.toEntity(book)));
            } catch (final DataIntegrityViolationException exception) {
                throw new DuplicateIsbnException();
            }
        });
    }

    @Override
    public Optional<Book> findByBookId(final String bookId) {
        return execute(() -> jpaRepository.findByBookId(bookId).map(BookMapper::toDomain));
    }

    @Override
    public boolean existsByIsbn(final String isbn) {
        return execute(() -> jpaRepository.existsByIsbn(isbn));
    }

}
