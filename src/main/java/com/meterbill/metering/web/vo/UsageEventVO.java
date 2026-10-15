package com.meterbill.metering.web.vo;

import java.time.Instant;

public record UsageEventVO(
        Long id,
        Long subscriptionId,
        String sourceId,
        String eventId,
        String meterCode,
        String modelCode,
        Long quantity,
        Instant occurredAt,
        Instant receivedAt,
        String status,
        Integer deliveryCount,
        String lastError,
        Instant processedAt) {
}
