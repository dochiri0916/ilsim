package com.x;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class SettlementJob {
    private final OrderRepository orderRepository;
    private final PaymentClient paymentClient;
    private final ClusterCoordinator clusterCoordinator;

    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void settle() {
        if (!clusterCoordinator.isPrimary()) {
            return;
        }
        for (Order order : orderRepository.findByStatus(OrderStatus.UNSETTLED)) {
            paymentClient.charge(order.getId(), order.getAmount());
            order.markSettled();
        }
    }
}
