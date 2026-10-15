
package com.meterbill.product.web.dto;

import jakarta.validation.constraints.*;

public record PreviewUsageRequest(
        @NotNull @PositiveOrZero Long quantity
) {
}
