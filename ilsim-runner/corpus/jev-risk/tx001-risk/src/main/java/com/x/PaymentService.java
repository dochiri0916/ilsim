package com.x;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentService {
    private final OrderRepository orderRepository;
    private final PaymentClient paymentClient;

    @Transactional
    public void pay(Long orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        paymentClient.approve(order.getId(), order.getAmount());
        order.markPaid();
    }
}
