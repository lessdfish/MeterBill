
package com.meterbill.product.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record PublishPriceVersionRequest(
        @NotNull
        Instant effectiveAt,

        @NotNull @Min(0)
        Integer revision
) {
}
