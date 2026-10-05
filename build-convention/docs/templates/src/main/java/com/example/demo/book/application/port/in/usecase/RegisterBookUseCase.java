package com.example.demo.book.application.port.in.usecase;

import com.example.demo.book.application.port.in.command.RegisterBookCommand;
import com.example.demo.book.application.port.in.result.RegisterBookResult;

@FunctionalInterface
public interface RegisterBookUseCase {

    RegisterBookResult register(RegisterBookCommand command);

}
