package com.meterbill.billing.web.vo;

public record InvoiceLineVO(
        Long id,
        Integer lineNo,
        String lineType,
        Long meterId,
        String description,
        String pricingMode,
        Long quantity,
        Long includedQuantity,
        Long billableQuantity,
        Long amountMinor,
        String rateSnapshot) {
}
