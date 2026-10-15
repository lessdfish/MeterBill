
package com.meterbill.product.web.vo;

import com.meterbill.product.domain.PricingMode;
import java.util.List;

public record UsageRuleVO(
        Long id,
        Long priceVersionId,
        Long meterId,
        String meterCode,
        PricingMode mode,
        Long includedQuantity,
        Long unitAmountMinor,
        List<TierVO> tiers,
        Integer priceVersionRevision
) {
    public record TierVO(
            Integer tierNo,
            Long upToQuantity,
            Long unitAmountMinor
    ) {
    }
}
