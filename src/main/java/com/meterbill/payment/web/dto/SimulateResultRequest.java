package com.meterbill.payment.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record SimulateResultRequest(
        @NotBlank @Pattern(regexp = "SUCCEEDED|FAILED|UNKNOWN") String result) {
}
