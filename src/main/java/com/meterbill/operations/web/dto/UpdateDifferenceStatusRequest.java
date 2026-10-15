package com.meterbill.operations.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateDifferenceStatusRequest(
        @NotBlank @Size(max = 20) String status,
        @NotBlank @Size(max = 1000) String note) {
}
