package com.example.demo.book.application.port.out;

import java.util.List;

public record BookSearchItems(List<BookSearchItem> values) {

    public BookSearchItems {
        values = List.copyOf(values);
    }

}
