
package com.meterbill.product;

import com.meterbill.product.domain.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UsageChargeCalculatorTest {

    private final List<UsageChargeCalculator.Tier> tiers = List.of(
            new UsageChargeCalculator.Tier(100L, 10),
            new UsageChargeCalculator.Tier(null, 8)
    );

    @Test
    void perUnitPricing() {
        var result = UsageChargeCalculator.calculate(
                PricingMode.PER_UNIT, 150, 20, 10L, List.of());

        assertEquals(130, result.billableQuantity());
        assertEquals(1300, result.usageAmountMinor());
    }

    @Test
    void graduatedPricing() {
        var result = UsageChargeCalculator.calculate(
                PricingMode.GRADUATED, 150, 20, null, tiers);

        assertEquals(130, result.billableQuantity());
        assertEquals(1240, result.usageAmountMinor());
    }

    @Test
    void volumePricing() {
        var result = UsageChargeCalculator.calculate(
                PricingMode.VOLUME, 150, 20, null, tiers);

        assertEquals(1040, result.usageAmountMinor());
    }

    @Test
    void includedQuantityMakesChargeZero() {
        var result = UsageChargeCalculator.calculate(
                PricingMode.PER_UNIT, 10, 20, 50L, List.of());

        assertEquals(0, result.billableQuantity());
        assertEquals(0, result.usageAmountMinor());
    }

    @Test
    void exactTierBoundary() {
        var result = UsageChargeCalculator.calculate(
                PricingMode.GRADUATED, 100, 0, null, tiers);

        assertEquals(1000, result.usageAmountMinor());
    }

    @Test
    void amountOverflowMustFail() {
        assertThrows(ArithmeticException.class, () ->
                UsageChargeCalculator.calculate(
                        PricingMode.PER_UNIT,
                        2,
                        0,
                        Long.MAX_VALUE,
                        List.of()));
    }

    @Test
    void negativeQuantityMustFail() {
        assertThrows(IllegalArgumentException.class, () ->
                UsageChargeCalculator.calculate(
                        PricingMode.PER_UNIT,
                        -1,
                        0,
                        10L,
                        List.of()));
    }
}
