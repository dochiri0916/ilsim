package com.x;

import lombok.RequiredArgsConstructor;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PartnerStockService {
    private final PartnerClient partnerClient;

    @Retryable(maxAttempts = 5)
    public PartnerStock fetch(String sku) {
        return partnerClient.stock(sku);
    }
}
