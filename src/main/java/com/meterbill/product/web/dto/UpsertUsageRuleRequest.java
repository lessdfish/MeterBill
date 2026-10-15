
package com.meterbill.product.web.dto;

import com.meterbill.product.domain.PricingMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;

public record UpsertUsageRuleRequest(
        @NotNull PricingMode mode,

        @NotNull @PositiveOrZero
        Long includedQuantity,

        @PositiveOrZero
        Long unitAmountMinor,

        @NotNull @Size(max = 50)
        List<@NotNull @Valid TierRequest> tiers,

        @NotNull @PositiveOrZero
        Integer revision
) {
    public record TierRequest(
            @Positive Long upToQuantity,

            @NotNull @PositiveOrZero
            Long unitAmountMinor
    ) {
    }
}
