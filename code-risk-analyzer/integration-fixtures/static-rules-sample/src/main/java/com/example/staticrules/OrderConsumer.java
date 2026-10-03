package com.example.staticrules;

import com.example.missing.OrderEvent;
import com.example.missing.OrderRepository;
import org.springframework.kafka.annotation.KafkaListener;

/** The repository and event types are intentionally absent so side effects are classified by name. */
public class OrderConsumer {
    private OrderRepository repository;

    // EXPECT: IDEM-004 (the listener "id" attribute is not a business key)
    @KafkaListener(id = "orders", topics = "orders")
    public void consume(OrderEvent event) { repository.save(event); }

    // EXPECT: no IDEM-004 (an event identifier is used)
    @KafkaListener(id = "orders-keyed", topics = "orders")
    public void consumeKeyed(OrderEvent event) { repository.save(event.getEventId()); }
}
