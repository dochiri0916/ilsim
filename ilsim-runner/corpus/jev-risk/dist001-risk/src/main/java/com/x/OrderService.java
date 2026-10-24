package com.x;

import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrderService {
    private final OrderRepository orderRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Transactional
    public Long place(Long memberId, long amount) {
        Order order = orderRepository.save(new Order(memberId, amount));
        kafkaTemplate.send("order-placed", String.valueOf(order.getId()));
        return order.getId();
    }
}
