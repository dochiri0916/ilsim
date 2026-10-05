package com.example.demo.book.adapter.out.persistence;

import com.example.demo.book.domain.Book;
import com.example.demo.book.domain.vo.BookId;
import com.example.demo.book.domain.vo.BookTitle;
import com.example.demo.book.domain.vo.Isbn;

final class BookMapper {

    private BookMapper() {
    }

    static Book toDomain(final BookEntity entity) {
        final String storedId = entity.getPublicId();
        final String storedIsbn = entity.getIsbn();
        final String storedTitle = entity.getTitle();
        final BookId id = BookId.parse(storedId);
        final Isbn isbn = Isbn.of(storedIsbn);
        final BookTitle title = BookTitle.of(storedTitle);
        return Book.restore(id, isbn, title);
    }

    static BookEntity toEntity(final Book domain) {
        final BookId id = domain.id();
        final Isbn isbn = domain.isbn();
        final BookTitle title = domain.title();
        return BookEntity.create(
                id.value(),
                isbn.value(),
                title.value());
    }

}
