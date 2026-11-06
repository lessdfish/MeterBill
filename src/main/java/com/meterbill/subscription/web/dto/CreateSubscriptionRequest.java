package com.meterbill.subscription.web.dto;

import jakarta.validation.constraints.NotNull;

public record CreateSubscriptionRequest(Long accountId, @NotNull Long planId) {

    public CreateSubscriptionRequest(Long planId) {
        this(null, planId);
    }
}
