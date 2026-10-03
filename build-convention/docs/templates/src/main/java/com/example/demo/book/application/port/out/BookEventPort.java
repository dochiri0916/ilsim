package com.example.demo.book.application.port.out;

import com.example.demo.book.domain.event.BookRegistered;

@FunctionalInterface
public interface BookEventPort {

    void publish(BookRegistered event);

}
