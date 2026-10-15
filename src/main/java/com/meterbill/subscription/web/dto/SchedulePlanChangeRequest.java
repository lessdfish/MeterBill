package com.meterbill.subscription.web.dto;

import jakarta.validation.constraints.NotNull;

public record SchedulePlanChangeRequest(@NotNull Long planId) {
}
