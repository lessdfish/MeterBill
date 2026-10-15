package com.meterbill.operations.web.vo;

import java.time.Instant;
import java.util.List;

public record ReconciliationRunVO(
        Long id,
        String idempotencyKey,
        String status,
        Instant startedAt,
        Instant completedAt,
        List<ReconciliationDifferenceVO> differences) {
}
