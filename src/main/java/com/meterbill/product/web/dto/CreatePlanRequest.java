
package com.meterbill.product.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreatePlanRequest(
        @NotBlank
        @Pattern(regexp = "^[A-Z][A-Z0-9_]{2,63}$")
        String planCode,

        @NotBlank
        @Size(max = 128)
        String name,

        @Size(max = 512)
        String description
) {
}
