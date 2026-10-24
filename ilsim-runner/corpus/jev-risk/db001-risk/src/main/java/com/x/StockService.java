package com.x;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StockService {
    private final ProductRepository productRepository;

    @Transactional
    public void order(Long productId, int quantity) {
        Product product = productRepository.findById(productId).orElseThrow();
        if (product.getStock() < quantity) {
            throw new IllegalStateException("재고 부족");
        }
        product.decreaseStock(quantity);
    }
}
