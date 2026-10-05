package com.example.demo.book.application.port.in.usecase;

import com.example.demo.book.application.port.in.query.GetBookQuery;
import com.example.demo.book.application.port.in.result.GetBookResult;

@FunctionalInterface
public interface GetBookUseCase {

    GetBookResult getBook(GetBookQuery query);

}
