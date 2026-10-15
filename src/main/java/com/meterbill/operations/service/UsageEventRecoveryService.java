package com.meterbill.operations.service;

import com.meterbill.common.exception.BusinessException;
import com.meterbill.common.exception.ErrorCode;
import com.meterbill.identity.service.SecurityAuditService;
import com.meterbill.operations.web.vo.UsageEventRetryVO;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UsageEventRecoveryService {

    private final JdbcTemplate jdbc;
    private final SecurityAuditService audit;

    @Transactional
    public UsageEventRetryVO retryFinalFailure(Long eventId, Long actorId) {
        List<EventStatus> events = jdbc.query("""
                SELECT tenant_id, status FROM mb_usage_event WHERE id = ? FOR UPDATE
                """, (rs, row) -> new EventStatus(
                rs.getLong("tenant_id"), rs.getString("status")), eventId);
        if (events.isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "用量事件不存在");
        }
        EventStatus event = events.getFirst();
        if ("PROCESSED".equals(event.status())) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "已处理的用量事件不能重新投递");
        }

        List<OutboxStatus> outboxes = jdbc.query("""
                SELECT id, status FROM mb_outbox
                WHERE event_type = 'USAGE_EVENT_ACCEPTED' AND event_id = ? FOR UPDATE
                """, (rs, row) -> new OutboxStatus(rs.getLong("id"), rs.getString("status")), eventId);
        if (outboxes.isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "用量事件 Outbox 记录不存在");
        }
        OutboxStatus outbox = outboxes.getFirst();
        if (!"FINAL_FAILED".equals(event.status()) && !"FINAL_FAILED".equals(outbox.status())) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT,
                    "只有最终失败的用量事件或 Outbox 才能手动重试");
        }

        if ("FINAL_FAILED".equals(event.status())) {
            jdbc.update("""
                    UPDATE mb_usage_event
                    SET status = 'RETRYABLE_FAILED', last_error = NULL
                    WHERE id = ? AND status = 'FINAL_FAILED'
                    """, eventId);
        }
        jdbc.update("""
                UPDATE mb_outbox
                SET status = 'READY', attempt_count = 0, next_retry_at = UTC_TIMESTAMP(6),
                    lease_until = NULL, last_error = NULL, published_at = NULL
                WHERE id = ?
                """, outbox.id());
        audit.record(event.tenantId(), actorId, "USAGE_EVENT_RETRY", "USAGE_EVENT",
                eventId.toString());
        return new UsageEventRetryVO(eventId,
                "FINAL_FAILED".equals(event.status()) ? "RETRYABLE_FAILED" : event.status(), "READY");
    }

    private record EventStatus(Long tenantId, String status) {
    }

    private record OutboxStatus(Long id, String status) {
    }
}
