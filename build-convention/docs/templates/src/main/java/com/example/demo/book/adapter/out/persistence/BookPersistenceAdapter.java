package com.example.demo.book.adapter.out.persistence;

import com.example.demo.book.application.exception.DuplicateIsbnException;
import com.example.demo.book.application.port.out.BookRepositoryPort;
import com.example.demo.book.application.port.out.BookSearchCondition;
import com.example.demo.book.application.port.out.BookSearchPage;
import com.example.demo.book.application.port.out.BookSearchItem;
import com.example.demo.book.application.port.out.BookSearchItems;
import java.util.List;
import com.example.demo.book.domain.Book;
import com.example.demo.book.domain.vo.BookId;
import com.example.demo.book.domain.vo.Isbn;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

import java.util.Optional;

import static com.example.demo.global.persistence.RepositoryExceptionTranslator.execute;
import static com.example.demo.global.persistence.RepositoryExceptionTranslator.isConstraintViolation;

@Repository
@RequiredArgsConstructor
final class BookPersistenceAdapter implements BookRepositoryPort {

    private final BookJpaRepository jpaRepository;
    private final BookQueryRepository queryRepository;

    @Override
    public Book create(final Book book) {
        return execute(() -> {
            try {
                BookEntity entity = BookMapper.toEntity(book);
                final BookEntity saved = jpaRepository.saveAndFlush(entity);
                return BookMapper.toDomain(saved);
            } catch (final DataIntegrityViolationException exception) {
                throw translateConflict(exception);
            }
        });
    }

    private static RuntimeException translateConflict(final DataIntegrityViolationException exception) {
        if (isConstraintViolation(exception, "uk_books_isbn")
                || isConstraintViolation(exception, "books.uk_books_isbn")) {
            return new DuplicateIsbnException(exception);
        }
        return exception;
    }

    @Override
    public Optional<Book> findByBookId(final BookId bookId) {
        final String value = bookId.value();
        return execute(() -> jpaRepository.findByPublicId(value)
                .map(BookMapper::toDomain));
    }

    @Override
    public boolean existsByIsbn(final Isbn isbn) {
        final String value = isbn.value();
        return execute(() -> jpaRepository.existsByIsbn(value));
    }

    @Override
    public BookSearchPage search(final BookSearchCondition condition) {
        return execute(() -> {
            List<BookSearchRow> rows = queryRepository.search(condition);
            final long total = queryRepository.count(condition);
            return toSearchPage(rows, total);
        });
    }

    private static BookSearchPage toSearchPage(final List<BookSearchRow> rows, final long total) {
        final List<BookSearchItem> items = rows.stream()
                .map(BookPersistenceAdapter::toSearchItem)
                .toList();
        return new BookSearchPage(new BookSearchItems(items), total);
    }

    private static BookSearchItem toSearchItem(final BookSearchRow row) {
        return new BookSearchItem(
                row.bookId(),
                row.isbn(),
                row.title());
    }

}
