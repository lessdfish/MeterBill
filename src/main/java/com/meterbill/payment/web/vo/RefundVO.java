package com.meterbill.payment.web.vo;

import java.time.Instant;

public record RefundVO(
        Long id,
        Long tenantId,
        Long paymentId,
        String externalReference,
        String currency,
        Long amountMinor,
        String status,
        Instant createdAt,
        Instant completedAt) {
}
