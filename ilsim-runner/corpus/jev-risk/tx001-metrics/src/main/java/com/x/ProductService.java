package com.x;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProductService {
    private final ProductRepository productRepository;
    private final InMemoryMetricsClient metricsClient;

    @Transactional
    public void rename(Long id, String name) {
        Product product = productRepository.findById(id).orElseThrow();
        product.rename(name);
        metricsClient.increment("product.renamed");
    }
}
