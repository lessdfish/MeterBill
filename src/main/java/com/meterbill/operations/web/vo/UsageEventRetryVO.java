package com.meterbill.operations.web.vo;

public record UsageEventRetryVO(Long eventId, String eventStatus, String outboxStatus) {
}
