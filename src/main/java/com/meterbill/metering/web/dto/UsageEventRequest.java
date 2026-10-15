package com.meterbill.metering.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record UsageEventRequest(
        @NotNull Long subscriptionId,
        @NotBlank @Size(max = 64) String sourceId,
        @NotBlank @Size(max = 128) String eventId,
        @NotBlank @Size(max = 64) String meterCode,
        @NotBlank @Size(max = 64) String modelCode,
        @NotNull @Positive Long quantity,
        @NotNull Instant occurredAt) {
}
