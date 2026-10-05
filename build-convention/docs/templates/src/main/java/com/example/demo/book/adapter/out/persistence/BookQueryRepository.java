package com.example.demo.book.adapter.out.persistence;

import com.example.demo.book.application.port.out.BookSearchCondition;
import com.example.demo.book.domain.vo.Isbn;
import com.querydsl.core.types.ConstructorExpression;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.DateTimePath;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.core.types.dsl.NumberPath;
import com.querydsl.core.types.dsl.StringPath;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

import static com.example.demo.book.adapter.out.persistence.QBookEntity.bookEntity;

@Component
@RequiredArgsConstructor
public final class BookQueryRepository {

    private final JPAQueryFactory queryFactory;

    public List<BookSearchRow> search(final BookSearchCondition condition) {
        final Isbn isbn = condition.isbn();
        final BooleanExpression filter = isbnEq(isbn);
        final long offset = condition.offset();
        final int size = condition.size();
        final DateTimePath<Instant> createdAt = bookEntity.createdAt;
        final NumberPath<Long> id = bookEntity.id;
        final OrderSpecifier<Instant> recent = createdAt.desc();
        final OrderSpecifier<Long> tie = id.desc();
        final ConstructorExpression<BookSearchRow> projection = Projections.constructor(
                        BookSearchRow.class,
                        bookEntity.publicId,
                        bookEntity.isbn,
                        bookEntity.title);
        return queryFactory.select(projection)
                .from(bookEntity)
                .where(filter)
                .orderBy(recent, tie)
                .offset(offset)
                .limit(size)
                .fetch();
    }

    public long count(final BookSearchCondition condition) {
        final Isbn isbn = condition.isbn();
        final BooleanExpression filter = isbnEq(isbn);
        final NumberPath<Long> id = bookEntity.id;
        final NumberExpression<Long> countExpression = id.count();
        final Long total = queryFactory.select(countExpression)
                .from(bookEntity)
                .where(filter)
                .fetchOne();
        if (total == null) {
            return 0L;
        }
        return total;
    }

    private static BooleanExpression isbnEq(final Isbn isbn) {
        if (isbn == null) {
            return null;
        }
        final String value = isbn.value();
        final StringPath isbnPath = bookEntity.isbn;
        return isbnPath.eq(value);
    }

}
