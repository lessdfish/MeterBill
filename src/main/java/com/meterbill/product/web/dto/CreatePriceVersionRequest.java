
package com.meterbill.product.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CreatePriceVersionRequest(
        @NotNull
        @Min(0)
        Long baseAmountMinor
) {
}
