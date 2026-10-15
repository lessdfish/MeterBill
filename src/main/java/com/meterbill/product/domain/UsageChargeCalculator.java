
package com.meterbill.product.domain;

import java.math.BigInteger;
import java.util.List;

public final class UsageChargeCalculator {

    private UsageChargeCalculator() {
    }

    public record Tier(
            Long upToQuantity,
            long unitAmountMinor
    ) {
    }

    public record Charge(
            long billableQuantity,
            long usageAmountMinor
    ) {
    }

    public static Charge calculate(
            PricingMode mode,
            long quantity,
            long includedQuantity,
            Long unitAmountMinor,
            List<Tier> tiers) {

        if (mode == null || quantity < 0 || includedQuantity < 0) {
            throw new IllegalArgumentException("Invalid usage quantity");
        }

        long billable = quantity > includedQuantity
                ? quantity - includedQuantity : 0L;

        BigInteger amount = switch (mode) {
            case PER_UNIT -> {
                if (unitAmountMinor == null || unitAmountMinor < 0) {
                    throw new IllegalArgumentException(
                            "Invalid unit amount");
                }
                yield multiply(billable, unitAmountMinor);
            }
            case GRADUATED -> graduated(billable, tiers);
            case VOLUME -> volume(billable, tiers);
        };

        // Fail rather than silently overflow BIGINT.
        return new Charge(billable, amount.longValueExact());
    }

    private static BigInteger graduated(
            long quantity, List<Tier> tiers) {

        if (quantity == 0) {
            return BigInteger.ZERO;
        }

        long previous = 0;
        BigInteger total = BigInteger.ZERO;

        for (Tier tier : tiers) {
            long upper = tier.upToQuantity() == null
                    ? Long.MAX_VALUE : tier.upToQuantity();

            long end = Math.min(quantity, upper);

            if (end > previous) {
                total = total.add(
                        multiply(end - previous, tier.unitAmountMinor()));
            }

            previous = end;

            if (end >= quantity) {
                return total;
            }
        }

        throw new IllegalStateException(
                "Pricing tiers do not cover the quantity");
    }

    private static BigInteger volume(
            long quantity, List<Tier> tiers) {

        if (quantity == 0) {
            return BigInteger.ZERO;
        }

        for (Tier tier : tiers) {
            if (tier.upToQuantity() == null
                    || quantity <= tier.upToQuantity()) {
                return multiply(quantity, tier.unitAmountMinor());
            }
        }

        throw new IllegalStateException(
                "Pricing tiers do not cover the quantity");
    }

    private static BigInteger multiply(long quantity, long amount) {
        if (quantity < 0 || amount < 0) {
            throw new IllegalArgumentException(
                    "Negative quantity or amount");
        }

        return BigInteger.valueOf(quantity)
                .multiply(BigInteger.valueOf(amount));
    }
}
