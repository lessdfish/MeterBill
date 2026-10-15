
package com.meterbill.product.web.dto;

import jakarta.validation.constraints.*;

public record CreateUsageMeterRequest(
        @NotBlank
        @Pattern(regexp = "^[A-Z][A-Z0-9_]{2,63}$")
        String meterCode,

        @NotBlank @Size(max = 128)
        String name,

        @NotBlank
        @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,31}$")
        String unit
) {
}
