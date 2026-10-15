package com.meterbill.operations.web.vo;

import java.time.Instant;

public record ReconciliationDifferenceVO(
        Long tenantId,
        Long id,
        String differenceType,
        String referenceType,
        String referenceId,
        String currency,
        Long expectedAmountMinor,
        Long actualAmountMinor,
        String expectedStatus,
        String actualStatus,
        String status,
        String evidence,
        String resolutionNote,
        Instant firstSeenAt,
        Instant lastSeenAt,
        Instant resolvedAt) {
}
