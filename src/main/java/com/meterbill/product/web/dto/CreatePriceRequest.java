
package com.meterbill.product.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CreatePriceRequest(
        @NotBlank
        @Pattern(regexp = "^[A-Z]{3}$")
        String currency,

        @NotBlank
        @Pattern(regexp = "MONTHLY|YEARLY")
        String billingPeriod
) {
}
