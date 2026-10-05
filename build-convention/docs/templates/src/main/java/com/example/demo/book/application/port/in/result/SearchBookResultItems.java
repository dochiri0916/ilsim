package com.example.demo.book.application.port.in.result;

import java.util.List;

public record SearchBookResultItems(List<SearchBookResultItem> values) {

    public SearchBookResultItems {
        values = List.copyOf(values);
    }

}
