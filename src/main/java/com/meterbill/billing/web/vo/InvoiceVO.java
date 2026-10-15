package com.meterbill.billing.web.vo;

import java.time.Instant;
import java.util.List;

public record InvoiceVO(
        Long id,
        Long tenantId,
        Long subscriptionId,
        Long periodId,
        Long priceVersionId,
        String currency,
        Instant periodStart,
        Instant periodEnd,
        Long baseAmountMinor,
        Long usageAmountMinor,
        Long totalAmountMinor,
        String status,
        Instant issuedAt,
        List<InvoiceLineVO> lines,
        List<InvoiceAdjustmentVO> adjustments) {
}
