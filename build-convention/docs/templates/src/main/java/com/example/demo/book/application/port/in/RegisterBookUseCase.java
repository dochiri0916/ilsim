package com.example.demo.book.application.port.in;

@FunctionalInterface
public interface RegisterBookUseCase {

    RegisterBookResult register(RegisterBookCommand command);

}
