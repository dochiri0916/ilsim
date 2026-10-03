package com.example.demo.book.adapter.out.event;

import com.example.demo.book.application.port.out.BookEventPort;
import com.example.demo.book.domain.event.BookRegistered;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class BookEventAdapter implements BookEventPort {

    private final ApplicationEventPublisher applicationEventPublisher;

    @Override
    public void publish(final BookRegistered event) {
        applicationEventPublisher.publishEvent(event);
    }

}
