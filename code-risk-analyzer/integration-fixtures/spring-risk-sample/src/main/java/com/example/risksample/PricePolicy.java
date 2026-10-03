package com.example.risksample;

/** Small, deterministic PIT target, independent of Spring and DB startup. */
public class PricePolicy {
    public int discountPercent(int quantity) {
        if (quantity < 1) throw new IllegalArgumentException("quantity must be positive");
        return quantity >= 10 ? 10 : 0;
    }
}
