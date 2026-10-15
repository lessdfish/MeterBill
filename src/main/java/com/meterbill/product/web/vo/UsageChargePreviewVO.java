package com.meterbill.product.web.vo;

import com.meterbill.product.domain.PricingMode;

public record UsageChargePreviewVO(
        Long priceVersionId,
        String currency,
        String meterCode,
        String unit,
        String priceVersionStatus,
        PricingMode mode,
        Long requestedQuantity,
        Long includedQuantity,
        Long billableQuantity,
        Long usageAmountMinor,
        Long baseAmountMinor
) {
}
