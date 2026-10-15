package com.meterbill.subscription.web.vo;

import java.time.Instant;

public record SubscriptionVO(
        Long id,
        Long tenantId,
        Long planId,
        String status,
        Long priceVersionId,
        String currency,
        Long baseAmountMinor,
        Instant activeFrom,
        Long periodId,
        Instant periodStart,
        Instant periodEnd) {
}
