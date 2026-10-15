
package com.meterbill.product.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdatePlanRequest(
        @NotBlank
        @Size(max = 128)
        String name,

        @Size(max = 512)
        String description,

        @NotNull
        @Min(0)
        Integer version
) {
}
