package com.meterbill.payment.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateRefundRequest(@NotNull @Positive Long amountMinor) {
}
