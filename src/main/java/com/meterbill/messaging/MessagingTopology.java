package com.meterbill.messaging;

public final class MessagingTopology {

    public static final String USAGE_EXCHANGE = "meterbill.usage.exchange";
    public static final String USAGE_ROUTING_KEY = "usage.accepted";
    public static final String USAGE_QUEUE = "meterbill.usage.queue";
    public static final String RETRY_EXCHANGE = "meterbill.usage.retry.exchange";
    public static final String RETRY_ROUTING_KEY = "usage.retry";
    public static final String RETRY_QUEUE = "meterbill.usage.retry";
    public static final String FAILED_EXCHANGE = "meterbill.usage.failed.exchange";
    public static final String FAILED_ROUTING_KEY = "usage.failed";
    public static final String FAILED_QUEUE = "meterbill.usage.failed";
    public static final String RETRY_HEADER = "x-meterbill-retry";

    private MessagingTopology() {
    }
}
