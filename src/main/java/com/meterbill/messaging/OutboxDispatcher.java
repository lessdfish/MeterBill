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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
        ClaimedBatch claimed = claim();
        var pending = new ArrayList<Pending>(claimed.items().size());
        for (DispatchItem item : claimed.items()) {
            pending.add(new Pending(item, publisher.publishAsync(MessagingTopology.USAGE_EXCHANGE,
                    MessagingTopology.USAGE_ROUTING_KEY, item.eventId().toString(), 0)));
        }
        complete(claimed.token(), pending);
    }

    private ClaimedBatch claim() {
        String claimToken = UUID.randomUUID().toString();
        int claimed = jdbc.update("""
                UPDATE mb_outbox
                SET status = 'PUBLISHING', attempt_count = attempt_count + 1,
                    lease_until = DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 30 SECOND),
                    claim_token = ?
                WHERE next_retry_at <= UTC_TIMESTAMP(6)
                  AND (status IN ('READY', 'RETRYABLE_FAILED')
                       OR (status = 'PUBLISHING' AND lease_until < UTC_TIMESTAMP(6)))
                ORDER BY id LIMIT ?
                """, claimToken, Math.clamp(batchSize, 1, 2048));
        if (claimed == 0) return new ClaimedBatch(claimToken, List.of());
        List<DispatchItem> items = jdbc.query("""
                        SELECT id, event_id, attempt_count FROM mb_outbox
                        WHERE claim_token = ? ORDER BY id
                        """,
                (rs, row) -> new DispatchItem(
                        rs.getLong("id"), rs.getLong("event_id"), rs.getInt("attempt_count")),
                claimToken);
        return new ClaimedBatch(claimToken, items);
    }

    private void complete(String claimToken, List<Pending> pending) {
        var published = new ArrayList<Object[]>();
        var failed = new ArrayList<Object[]>();
        for (Pending publication : pending) {
            DispatchItem item = publication.item();
            try {
                publication.confirmation().join();
                published.add(new Object[]{item.id(), item.attemptCount()});
            } catch (RuntimeException ex) {
                metrics.recordMqFailure("outbox", item.attemptCount() >= MAX_ATTEMPTS);
                String status = item.attemptCount() >= MAX_ATTEMPTS
                        ? "FINAL_FAILED" : "RETRYABLE_FAILED";
                long delaySeconds = Math.min(300, 1L << Math.min(item.attemptCount(), 8));
                LocalDateTime retryAt = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(delaySeconds);
                failed.add(new Object[]{status, retryAt, safeMessage(ex), item.id(), item.attemptCount()});
                log.warn("Outbox publication failed for id {} (attempt {})",
                        item.id(), item.attemptCount(), ex);
            }
        }
        if (failed.isEmpty()) {
            if (!published.isEmpty()) {
                jdbc.update("""
                        UPDATE mb_outbox
                        SET status = 'PUBLISHED', published_at = UTC_TIMESTAMP(6),
                            lease_until = NULL, claim_token = NULL, last_error = NULL
                        WHERE claim_token = ? AND status = 'PUBLISHING'
                        """, claimToken);
            }
            return;
        }
        if (!published.isEmpty()) {
            jdbc.batchUpdate("""
                    UPDATE mb_outbox
                    SET status = 'PUBLISHED', published_at = UTC_TIMESTAMP(6),
                        lease_until = NULL, claim_token = NULL, last_error = NULL
                    WHERE id = ? AND status = 'PUBLISHING' AND attempt_count = ?
                    """, published);
        }
        if (!failed.isEmpty()) {
            jdbc.batchUpdate("""
                    UPDATE mb_outbox
                    SET status = ?, next_retry_at = ?, lease_until = NULL,
                        claim_token = NULL, last_error = ?
                    WHERE id = ? AND status = 'PUBLISHING' AND attempt_count = ?
                    """, failed);
        }
    }

    private String safeMessage(Exception ex) {
        String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        return message.length() <= 900 ? message : message.substring(0, 900);
    }

    private record DispatchItem(Long id, Long eventId, int attemptCount) {
    }
    private record ClaimedBatch(String token, List<DispatchItem> items) { }
    private record Pending(DispatchItem item, java.util.concurrent.CompletableFuture<Void> confirmation) { }
}
