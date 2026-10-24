package com.x;

import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class PaymentEventConsumer {
    private final PointRepository pointRepository;

    @KafkaListener(topics = "payment-completed", groupId = "point")
    @Transactional
    public void onPaymentCompleted(PaymentCompleted event) {
        pointRepository.save(new PointHistory(event.memberId(), event.amount() / 100));
    }
}
