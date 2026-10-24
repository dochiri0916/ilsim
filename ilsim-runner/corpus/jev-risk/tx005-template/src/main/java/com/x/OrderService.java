package com.x;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class OrderService {
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final TransactionTemplate transactionTemplate;

    public Long place(Long memberId, List<OrderLine> lines) {
        return transactionTemplate.execute(status -> {
            Order order = orderRepository.save(new Order(memberId));
            orderItemRepository.saveAll(lines.stream()
                    .map(line -> new OrderItem(order.getId(), line.productId(), line.quantity()))
                    .toList());
            return order.getId();
        });
    }
}
