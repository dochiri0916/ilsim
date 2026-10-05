package com.example.demo.book.adapter.in.web.request;

import com.example.demo.book.application.port.in.command.RegisterBookCommand;
import jakarta.validation.constraints.NotBlank;

public record RegisterBookRequest(
        @NotBlank(message = "ISBN은 필수입니다.") String isbn,
        @NotBlank(message = "도서 제목은 필수입니다.") String title) {

    public RegisterBookCommand toCommand() {
        return new RegisterBookCommand(isbn, title);
    }

}
