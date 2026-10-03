package com.example.risksample;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PricePolicyTests {
    private final PricePolicy policy = new PricePolicy();

    @Test
    void rejectsNonPositiveQuantities() {
        assertThrows(IllegalArgumentException.class, () -> policy.discountPercent(0));
        assertThrows(IllegalArgumentException.class, () -> policy.discountPercent(-1));
    }

    @Test
    void appliesDiscountAtTheExactBoundary() {
        assertEquals(0, policy.discountPercent(1));
        assertEquals(0, policy.discountPercent(9));
        assertEquals(10, policy.discountPercent(10));
        assertEquals(10, policy.discountPercent(11));
    }
}
