package com.meterbill.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class BusinessMetrics {

    private final JdbcTemplate jdbc;
    private final AtomicLong outboxBacklog = new AtomicLong();
    private final AtomicLong oldestOutboxSeconds = new AtomicLong();
    private final AtomicLong meteringBacklog = new AtomicLong();
    private final AtomicLong billingDuePeriods = new AtomicLong();
    private final AtomicLong reconciliationDifferences = new AtomicLong();
    private final Counter billingFailures;
    private final Counter consumerRetries;
    private final Counter consumerFinalFailures;
    private final Counter outboxRetries;
    private final Counter outboxFinalFailures;

    public BusinessMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        Gauge.builder("meterbill.outbox.backlog", outboxBacklog, AtomicLong::get)
                .description("Outbox rows waiting for successful publication").register(registry);
        Gauge.builder("meterbill.outbox.oldest.age.seconds", oldestOutboxSeconds, AtomicLong::get)
                .description("Age of the oldest unpublished outbox row").baseUnit("seconds").register(registry);
        Gauge.builder("meterbill.metering.backlog", meteringBacklog, AtomicLong::get)
                .description("Usage events waiting for successful aggregation").register(registry);
        Gauge.builder("meterbill.billing.due.periods", billingDuePeriods, AtomicLong::get)
                .description("Ended subscription periods without an invoice").register(registry);
        Gauge.builder("meterbill.reconciliation.differences", reconciliationDifferences, AtomicLong::get)
                .description("Open or investigating reconciliation differences").register(registry);
        billingFailures = Counter.builder("meterbill.billing.failures").description("Automatic invoice issue failures").register(registry);
        consumerRetries = mqCounter(registry, "consumer", "retryable");
        consumerFinalFailures = mqCounter(registry, "consumer", "final");
        outboxRetries = mqCounter(registry, "outbox", "retryable");
        outboxFinalFailures = mqCounter(registry, "outbox", "final");
    }

    private Counter mqCounter(MeterRegistry registry, String stage, String outcome) {
        return Counter.builder("meterbill.mq.failures")
                .description("Message publication or consumption failures")
                .tag("stage", stage).tag("outcome", outcome).register(registry);
    }

    @Scheduled(fixedDelayString = "${meterbill.observability.refresh-interval:15000}")
    public void refresh() {
        try {
            outboxBacklog.set(value("SELECT COUNT(*) FROM mb_outbox WHERE status IN ('READY','PUBLISHING','RETRYABLE_FAILED')"));
            oldestOutboxSeconds.set(value("SELECT COALESCE(MAX(TIMESTAMPDIFF(SECOND,created_at,UTC_TIMESTAMP())),0) FROM mb_outbox WHERE status IN ('READY','PUBLISHING','RETRYABLE_FAILED')"));
            meteringBacklog.set(value("SELECT COUNT(*) FROM mb_usage_event WHERE status IN ('RECEIVED','RETRYABLE_FAILED')"));
            billingDuePeriods.set(value("SELECT COUNT(*) FROM mb_subscription_period sp LEFT JOIN mb_invoice i ON i.period_id=sp.id WHERE sp.period_end<=UTC_TIMESTAMP(6) AND i.id IS NULL"));
            reconciliationDifferences.set(value("SELECT COUNT(*) FROM mb_reconciliation_difference WHERE status IN ('OPEN','INVESTIGATING')"));
        } catch (RuntimeException ex) {
            log.warn("Could not refresh business metrics", ex);
        }
    }

    public void recordMqFailure(String stage, boolean finalFailure) {
        Counter counter = switch (stage) {
            case "consumer" -> finalFailure ? consumerFinalFailures : consumerRetries;
            case "outbox" -> finalFailure ? outboxFinalFailures : outboxRetries;
            default -> throw new IllegalArgumentException("Unknown MQ stage: " + stage);
        };
        counter.increment();
    }

    public void recordBillingFailure() {
        billingFailures.increment();
    }

    private long value(String sql) {
        Long result = jdbc.queryForObject(sql, Long.class);
        return result == null ? 0 : result;
    }
}
