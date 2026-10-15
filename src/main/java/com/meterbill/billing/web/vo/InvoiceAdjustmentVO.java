package com.meterbill.billing.web.vo;

import java.time.Instant;
import java.util.List;

public record InvoiceAdjustmentVO(
        Long id,
        Long invoiceId,
        Long periodId,
        Integer adjustmentNo,
        String currency,
        Long amountMinor,
        Integer sourceEventCount,
        String reason,
        String status,
        Instant createdAt,
        List<InvoiceLineVO> lines) {
}
