package com.meterbill.metering.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UsageEventProcessor {

    private final JdbcTemplate jdbc;

    @Transactional
    public void process(Long eventId) {
        List<EventRow> rows = jdbc.query("""
                SELECT tenant_id, subscription_id, period_id, meter_id, quantity, status
                FROM mb_usage_event WHERE id = ? FOR UPDATE
                """, (rs, row) -> new EventRow(
                rs.getLong("tenant_id"), rs.getLong("subscription_id"),
                rs.getLong("period_id"), rs.getLong("meter_id"),
                rs.getLong("quantity"), rs.getString("status")), eventId);
        if (rows.isEmpty()) {
            throw new IllegalStateException("Usage event does not exist: " + eventId);
        }
        EventRow event = rows.getFirst();
        if ("FINAL_FAILED".equals(event.status())) {
            return;
        }

        jdbc.update("UPDATE mb_usage_event SET delivery_count = delivery_count + 1 WHERE id = ?", eventId);
        int firstDelivery = jdbc.update("""
                INSERT IGNORE INTO mb_usage_event_processed (usage_event_id)
                VALUES (?)
                """, eventId);
        if (firstDelivery == 1) {
            jdbc.update("""
                    INSERT INTO mb_usage_period_total
                        (tenant_id, subscription_id, period_id, meter_id,
                         total_quantity, revision)
                    VALUES (?, ?, ?, ?, ?, 1)
                    ON DUPLICATE KEY UPDATE
                        total_quantity = total_quantity + VALUES(total_quantity),
                        revision = revision + 1
                    """, event.tenantId(), event.subscriptionId(), event.periodId(),
                    event.meterId(), event.quantity());
        }
        jdbc.update("""
                UPDATE mb_usage_event
                SET status = 'PROCESSED', processed_at = COALESCE(processed_at, UTC_TIMESTAMP(6)),
                    last_error = NULL
                WHERE id = ?
                """, eventId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(Long eventId, boolean finalFailure, String message) {
        jdbc.update("""
                UPDATE mb_usage_event
                SET status = ?, delivery_count = delivery_count + 1, last_error = ?
                WHERE id = ? AND status NOT IN ('PROCESSED', 'FINAL_FAILED')
                """, finalFailure ? "FINAL_FAILED" : "RETRYABLE_FAILED",
                truncate(message), eventId);
    }

    private String truncate(String message) {
        if (message == null || message.isBlank()) {
            return "Message processing failed";
        }
        return message.length() <= 900 ? message : message.substring(0, 900);
    }

    private record EventRow(
            Long tenantId, Long subscriptionId, Long periodId, Long meterId,
            Long quantity, String status) {
    }
}
