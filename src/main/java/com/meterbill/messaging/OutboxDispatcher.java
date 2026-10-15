package com.meterbill.messaging;

import com.meterbill.observability.BusinessMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "meterbill.messaging", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class OutboxDispatcher {

    private static final int MAX_ATTEMPTS = 8;
    private final JdbcTemplate jdbc;
    private final ConfirmedRabbitPublisher publisher;
    private final BusinessMetrics metrics;
    @Value("${meterbill.messaging.dispatch-batch-size:128}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${meterbill.messaging.dispatch-interval:1000}")
    public void dispatchPending() {
        List<Long> candidates = jdbc.query("""
                SELECT id FROM mb_outbox
                WHERE next_retry_at <= UTC_TIMESTAMP(6)
                  AND (status IN ('READY', 'RETRYABLE_FAILED')
                       OR (status = 'PUBLISHING' AND lease_until < UTC_TIMESTAMP(6)))
                ORDER BY id LIMIT ?
                """, (rs, row) -> rs.getLong(1), Math.clamp(batchSize, 1, 512));
        var pending = new java.util.ArrayList<Pending>();
        for (Long outboxId : candidates) {
            DispatchItem item = claim(outboxId);
            if (item != null) {
                pending.add(new Pending(item, publisher.publishAsync(MessagingTopology.USAGE_EXCHANGE,
                        MessagingTopology.USAGE_ROUTING_KEY, item.eventId().toString(), 0)));
                if (pending.getLast().confirmation().isCompletedExceptionally()) break;
            }
        }
        // Wait outside the send loop: every row still requires its own positive publisher confirm.
        for (Pending publication : pending) complete(publication.item(), publication.confirmation());
    }

    private DispatchItem claim(Long id) {
        int claimed = jdbc.update("""
                UPDATE mb_outbox
                SET status = 'PUBLISHING', attempt_count = attempt_count + 1,
                    lease_until = DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 30 SECOND)
                WHERE id = ? AND next_retry_at <= UTC_TIMESTAMP(6)
                  AND (status IN ('READY', 'RETRYABLE_FAILED')
                       OR (status = 'PUBLISHING' AND lease_until < UTC_TIMESTAMP(6)))
                """, id);
        if (claimed == 0) {
            return null;
        }
        List<DispatchItem> rows = jdbc.query("""
                SELECT id, event_id, attempt_count FROM mb_outbox WHERE id = ?
                """, (rs, row) -> new DispatchItem(
                rs.getLong("id"), rs.getLong("event_id"), rs.getInt("attempt_count")), id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void complete(DispatchItem item, java.util.concurrent.CompletableFuture<Void> confirmation) {
        try {
            confirmation.join();
            jdbc.update("""
                    UPDATE mb_outbox
                    SET status = 'PUBLISHED', published_at = UTC_TIMESTAMP(6),
                        lease_until = NULL, last_error = NULL
                    WHERE id = ? AND status = 'PUBLISHING' AND attempt_count = ?
                    """, item.id(), item.attemptCount());
        } catch (RuntimeException ex) {
            metrics.recordMqFailure("outbox", item.attemptCount() >= MAX_ATTEMPTS);
            String status = item.attemptCount() >= MAX_ATTEMPTS
                    ? "FINAL_FAILED" : "RETRYABLE_FAILED";
            long delaySeconds = Math.min(300, 1L << Math.min(item.attemptCount(), 8));
            LocalDateTime retryAt = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(delaySeconds);
            jdbc.update("""
                    UPDATE mb_outbox
                    SET status = ?, next_retry_at = ?, lease_until = NULL, last_error = ?
                    WHERE id = ? AND status = 'PUBLISHING' AND attempt_count = ?
                    """, status, retryAt, safeMessage(ex), item.id(), item.attemptCount());
            log.warn("Outbox publication failed for id {} (attempt {})",
                    item.id(), item.attemptCount(), ex);
        }
    }

    private String safeMessage(Exception ex) {
        String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        return message.length() <= 900 ? message : message.substring(0, 900);
    }

    private record DispatchItem(Long id, Long eventId, int attemptCount) {
    }
    private record Pending(DispatchItem item, java.util.concurrent.CompletableFuture<Void> confirmation) { }
}
