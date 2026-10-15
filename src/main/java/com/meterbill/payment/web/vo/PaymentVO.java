package com.meterbill.payment.web.vo;

import java.time.Instant;

public record PaymentVO(
        Long id,
        Long tenantId,
        Long invoiceId,
        String externalReference,
        String currency,
        Long amountMinor,
        String status,
        Instant createdAt,
        Instant completedAt) {
}
