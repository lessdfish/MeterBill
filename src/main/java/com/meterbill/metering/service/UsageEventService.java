package com.meterbill.metering.service;

import com.meterbill.common.exception.BusinessException;
import com.meterbill.common.exception.ErrorCode;
import com.meterbill.metering.web.dto.UsageEventRequest;
import com.meterbill.metering.web.vo.UsageEventVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;

@Service
@RequiredArgsConstructor
public class UsageEventService {

    private final JdbcTemplate jdbc;
    private final ObjectProvider<UsageMeterCache> meterCache;

    @Transactional
    public UsageEventVO accept(Long tenantId, UsageEventRequest request) {
        String payloadHash = payloadHash(request);
        ExistingEvent duplicate = findByExternalId(tenantId, request.sourceId(), request.eventId());
        if (duplicate != null) {
            requireSamePayload(duplicate.payloadHash(), payloadHash);
            return getById(tenantId, duplicate.id());
        }

        if (request.occurredAt().isAfter(Instant.now())) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "occurredAt 不能晚于当前时间");
        }

        SubscriptionPeriod period = findPeriod(tenantId, request);
        if (period == null) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT,
                    "用量事件不属于该租户订阅的有效账期或计量项");
        }
        if ("CANCELED".equals(period.subscriptionStatus())
                && request.occurredAt().isAfter(period.subscriptionUpdatedAt().toInstant(ZoneOffset.UTC))) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT,
                    "立即取消后的时间不能接收用量事件");
        }

        Long eventDbId;
        try {
            var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
            jdbc.update(connection -> {
                var statement = connection.prepareStatement("""
                    INSERT INTO mb_usage_event
                        (tenant_id, subscription_id, period_id, meter_id,
                         source_id, event_id, model_code, quantity, occurred_at,
                         payload_hash, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'RECEIVED')
                    """, java.sql.Statement.RETURN_GENERATED_KEYS);
                Object[] values = {tenantId, request.subscriptionId(), period.id(), period.meterId(),
                        request.sourceId(), request.eventId(), request.modelCode(), request.quantity(),
                        utc(request.occurredAt()), payloadHash};
                for (int index=0; index<values.length; index++) statement.setObject(index+1, values[index]);
                return statement;
            }, keys);
            eventDbId = java.util.Objects.requireNonNull(keys.getKey()).longValue();
        } catch (DuplicateKeyException ex) {
            ExistingEvent raced = findByExternalId(tenantId, request.sourceId(), request.eventId(), true);
            if (raced == null) {
                throw ex;
            }
            requireSamePayload(raced.payloadHash(), payloadHash);
            return readById(tenantId, raced.id(), true);
        }

        jdbc.update("""
                INSERT INTO mb_outbox (event_type, event_id, status)
                VALUES ('USAGE_EVENT_ACCEPTED', ?, 'READY')
                """, eventDbId);
        return getById(tenantId, eventDbId);
    }

    @Transactional(readOnly = true)
    public UsageEventVO get(Long tenantId, String sourceId, String eventId) {
        List<Long> ids = jdbc.query("""
                SELECT id FROM mb_usage_event
                WHERE tenant_id = ? AND source_id = ? AND event_id = ?
                """, (rs, row) -> rs.getLong(1), tenantId, sourceId, eventId);
        if (ids.isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "用量事件不存在");
        }
        return getById(tenantId, ids.getFirst());
    }

    @Transactional(readOnly = true)
    public UsageEventVO getById(Long tenantId, Long id) {
        return readById(tenantId, id, false);
    }

    private UsageEventVO readById(Long tenantId, Long id, boolean current) {
        List<UsageEventVO> rows = jdbc.query("""
                SELECT e.id, e.subscription_id, e.source_id, e.event_id,
                       m.meter_code, e.model_code, e.quantity, e.occurred_at,
                       e.received_at, e.status, e.delivery_count, e.last_error,
                       e.processed_at
                FROM mb_usage_event e
                JOIN mb_usage_meter m ON m.id = e.meter_id
                WHERE e.tenant_id = ? AND e.id = ?
                """ + (current ? " FOR SHARE" : ""), (rs, row) -> new UsageEventVO(
                rs.getLong("id"), rs.getLong("subscription_id"),
                rs.getString("source_id"), rs.getString("event_id"),
                rs.getString("meter_code"), rs.getString("model_code"),
                rs.getLong("quantity"), instant(rs.getObject("occurred_at", LocalDateTime.class)),
                instant(rs.getObject("received_at", LocalDateTime.class)),
                rs.getString("status"), rs.getInt("delivery_count"), rs.getString("last_error"),
                instant(rs.getObject("processed_at", LocalDateTime.class))), tenantId, id);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "用量事件不存在");
        }
        return rows.getFirst();
    }

    private SubscriptionPeriod findPeriod(Long tenantId, UsageEventRequest request) {
        List<SubscriptionPeriod> rows = jdbc.query("""
                SELECT sp.id AS period_id, p.product_id,
                       s.status AS subscription_status, s.updated_at AS subscription_updated_at
                FROM mb_subscription s
                JOIN mb_subscription_period sp
                  ON sp.subscription_id = s.id AND sp.tenant_id = s.tenant_id
                JOIN mb_plan p ON p.id = sp.plan_id
                WHERE s.tenant_id = ? AND s.id = ? AND s.status <> 'PENDING'
                  AND sp.active_from <= ?
                  AND sp.period_start <= ? AND ? < sp.period_end
                  AND EXISTS (SELECT 1 FROM mb_usage_price_rule r JOIN mb_usage_meter m ON m.id=r.meter_id
                              WHERE r.price_version_id=sp.price_version_id AND m.product_id=p.product_id AND m.meter_code=?)
                ORDER BY sp.period_start DESC
                LIMIT 1
                """, (rs, row) -> new SubscriptionPeriod(
                rs.getLong("period_id"), rs.getLong("product_id"), null,
                rs.getString("subscription_status"), rs.getObject("subscription_updated_at", LocalDateTime.class)),
                tenantId, request.subscriptionId(),
                utc(request.occurredAt()), utc(request.occurredAt()), utc(request.occurredAt()), request.meterCode());
        if (rows.isEmpty()) {
            return null;
        }
        SubscriptionPeriod period = rows.getFirst();
        UsageMeterCache cache = meterCache.getIfAvailable();
        Long meterId = cache == null
                ? findMeterId(period.productId(), request.meterCode(), request.modelCode())
                : cache.findMeterId(period.productId(), request.meterCode(), request.modelCode());
        return meterId == null ? null : period.withMeterId(meterId);
    }

    private Long findMeterId(Long productId, String meterCode, String modelCode) {
        List<Long> ids = jdbc.query("""
                SELECT m.id FROM mb_usage_meter m
                JOIN mb_model_meter mm ON mm.meter_id=m.id JOIN mb_ai_model a ON a.id=mm.model_id
                WHERE m.product_id=? AND m.meter_code=? AND a.product_id=m.product_id AND a.model_code=?
                """, (rs,row)->rs.getLong(1),productId,meterCode,modelCode);
        return ids.isEmpty() ? null : ids.getFirst();
    }
    private ExistingEvent findByExternalId(Long tenantId, String sourceId, String eventId) {
        return findByExternalId(tenantId, sourceId, eventId, false);
    }

    private ExistingEvent findByExternalId(Long tenantId, String sourceId, String eventId, boolean current) {
        List<ExistingEvent> rows = jdbc.query("""
                SELECT id, payload_hash FROM mb_usage_event
                WHERE tenant_id = ? AND source_id = ? AND event_id = ?
                """ + (current ? " FOR SHARE" : ""), (rs, row) -> new ExistingEvent(rs.getLong("id"), rs.getString("payload_hash")),
                tenantId, sourceId, eventId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void requireSamePayload(String actualHash, String requestedHash) {
        if (!MessageDigest.isEqual(actualHash.getBytes(StandardCharsets.US_ASCII),
                requestedHash.getBytes(StandardCharsets.US_ASCII))) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT,
                    "sourceId 和 eventId 已用于不同的用量载荷");
        }
    }

    private String payloadHash(UsageEventRequest request) {
        String canonical = String.join("\n",
                request.subscriptionId().toString(), request.sourceId(), request.eventId(),
                request.meterCode(), request.modelCode(), request.quantity().toString(),
                request.occurredAt().toString());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private record ExistingEvent(Long id, String payloadHash) {
    }

    private record SubscriptionPeriod(Long id, Long productId, Long meterId,
                                      String subscriptionStatus,
                                      LocalDateTime subscriptionUpdatedAt) {
        private SubscriptionPeriod withMeterId(Long value) {
            return new SubscriptionPeriod(id, productId, value,
                    subscriptionStatus, subscriptionUpdatedAt);
        }
    }
}
