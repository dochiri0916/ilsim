package com.example.demo.book.adapter.out.persistence;

import com.example.demo.book.domain.Book;
import com.example.demo.book.domain.vo.BookId;
import com.example.demo.book.domain.vo.BookTitle;
import com.example.demo.book.domain.vo.Isbn;

final class BookMapper {

    private BookMapper() {
    }

    static Book toDomain(final BookEntity entity) {
        return Book.restore(new BookId(entity.getBookId()), new Isbn(entity.getIsbn()), new BookTitle(entity.getTitle()));
    }

    static BookEntity toEntity(final Book domain) {
        return BookEntity.create(domain.id().value(), domain.isbn().value(), domain.title().value());
    }

}
