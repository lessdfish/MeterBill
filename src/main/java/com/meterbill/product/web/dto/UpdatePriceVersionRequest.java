
package com.meterbill.product.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record UpdatePriceVersionRequest(
        @NotNull @Min(0)
        Long baseAmountMinor,

        @NotNull @Min(0)
        Integer revision
) {
}
