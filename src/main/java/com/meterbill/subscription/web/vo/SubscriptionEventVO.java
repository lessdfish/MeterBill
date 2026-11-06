package com.meterbill.subscription.web.vo;

import java.time.Instant;

public record SubscriptionEventVO(Long id, Long subscriptionId, String eventType,
                                  String fromStatus, String toStatus,
                                  Long fromPlanId, Long toPlanId,
                                  Instant requestedAt, Instant effectiveAt,
                                  String metadataJson) {
}
