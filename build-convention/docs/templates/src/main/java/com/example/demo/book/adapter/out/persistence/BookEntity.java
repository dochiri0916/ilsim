package com.example.demo.book.adapter.out.persistence;

import com.example.demo.global.persistence.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "books", uniqueConstraints = {
        @UniqueConstraint(name = "uk_books_public_id", columnNames = "public_id"),
        @UniqueConstraint(name = "uk_books_isbn", columnNames = "isbn")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BookEntity extends BaseEntity {

    @Getter(AccessLevel.NONE)
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, length = 36)
    private String publicId;

    @Column(nullable = false, length = 13)
    private String isbn;

    @Column(nullable = false, length = 100)
    private String title;

    private BookEntity(final String publicId, final String isbn, final String title) {
        this.publicId = publicId;
        this.isbn = isbn;
        this.title = title;
    }

    public static BookEntity create(final String publicId, final String isbn, final String title) {
        return new BookEntity(publicId, isbn, title);
    }

}
