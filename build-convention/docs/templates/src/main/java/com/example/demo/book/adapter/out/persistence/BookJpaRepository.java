package com.example.demo.book.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

interface BookJpaRepository extends JpaRepository<BookEntity, Long> {

    Optional<BookEntity> findByBookId(String bookId);

    boolean existsByIsbn(String isbn);

}
