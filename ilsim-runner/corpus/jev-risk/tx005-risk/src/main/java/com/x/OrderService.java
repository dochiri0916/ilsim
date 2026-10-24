package com.x;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OrderService {
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;

    public Long place(Long memberId, List<OrderLine> lines) {
        Order order = orderRepository.save(new Order(memberId));
        orderItemRepository.saveAll(lines.stream()
                .map(line -> new OrderItem(order.getId(), line.productId(), line.quantity()))
                .toList());
        return order.getId();
    }
}
