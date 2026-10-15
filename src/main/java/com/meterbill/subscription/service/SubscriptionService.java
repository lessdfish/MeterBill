package com.meterbill.subscription.service;

import com.meterbill.common.exception.BusinessException;
import com.meterbill.common.exception.ErrorCode;
import com.meterbill.identity.service.SecurityAuditService;
import com.meterbill.subscription.domain.SubscriptionStatus;
import com.meterbill.subscription.web.vo.SubscriptionVO;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private final JdbcTemplate jdbc;
    private final SecurityAuditService audit;

    @Transactional
    public SubscriptionVO create(Long tenantId, String idempotencyKey, Long planId) {
        validateIdempotencyKey(idempotencyKey);
        String requestHash = hash(planId.toString());
        SubscriptionRow existing = findByIdempotencyKey(tenantId, idempotencyKey);
        if (existing != null) {
            requireSameRequest(existing.requestHash(), requestHash);
            return view(tenantId, existing.id());
        }

        requireActivePlan(planId);
        try {
            jdbc.update("""
                    INSERT INTO mb_subscription
                        (tenant_id, plan_id, idempotency_key, request_hash, status)
                    VALUES (?, ?, ?, ?, 'PENDING')
                    """, tenantId, planId, idempotencyKey, requestHash);
        } catch (DuplicateKeyException ex) {
            SubscriptionRow raced = findByIdempotencyKey(tenantId, idempotencyKey);
            if (raced == null) {
                throw ex;
            }
            requireSameRequest(raced.requestHash(), requestHash);
            return view(tenantId, raced.id());
        }

        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        audit.record(tenantId, null, "SUBSCRIPTION_CREATE", "SUBSCRIPTION", id.toString());
        return view(tenantId, id);
    }

    @Transactional(readOnly = true)
    public SubscriptionVO get(Long tenantId, Long subscriptionId) {
        return view(tenantId, subscriptionId);
    }

    @Transactional
    public SubscriptionVO activate(Long tenantId, Long subscriptionId) {
        SubscriptionRow subscription = requireSubscription(tenantId, subscriptionId, true);
        if (SubscriptionStatus.ACTIVE.name().equals(subscription.status())) {
            return view(tenantId, subscriptionId);
        }
        if (!SubscriptionStatus.PENDING.name().equals(subscription.status())) {
            throw conflict("当前订阅状态不能激活");
        }

        PlanPrice price = requireMonthlyPrice(subscription.planId(), Instant.now());
        ZoneId zone = tenantZone(tenantId);
        Instant now = Instant.now();
        YearMonth currentMonth = YearMonth.from(now.atZone(zone));
        Instant periodStart = currentMonth.atDay(1).atStartOfDay(zone).toInstant();
        Instant periodEnd = currentMonth.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
        insertPeriod(tenantId, subscriptionId, subscription.planId(), price,
                now, periodStart, periodEnd);

        int changed = jdbc.update("""
                UPDATE mb_subscription
                SET status = 'ACTIVE', version = version + 1
                WHERE id = ? AND tenant_id = ? AND status = 'PENDING'
                """, subscriptionId, tenantId);
        if (changed != 1) {
            throw conflict("订阅状态已变化，请刷新后重试");
        }
        audit.record(tenantId, null, "SUBSCRIPTION_ACTIVATE", "SUBSCRIPTION", subscriptionId.toString());
        return view(tenantId, subscriptionId);
    }

    @Transactional
    public SubscriptionVO cancel(Long tenantId, Long subscriptionId, boolean immediate) {
        SubscriptionRow subscription = requireSubscription(tenantId, subscriptionId, true);
        if (SubscriptionStatus.CANCELED.name().equals(subscription.status())
                || SubscriptionStatus.EXPIRED.name().equals(subscription.status())) {
            return view(tenantId, subscriptionId);
        }
        if (SubscriptionStatus.CANCEL_SCHEDULED.name().equals(subscription.status())
                && !immediate) {
            return view(tenantId, subscriptionId);
        }
        if (SubscriptionStatus.PENDING.name().equals(subscription.status()) && immediate) {
            updateStatus(tenantId, subscriptionId, "PENDING", "CANCELED");
        } else if (SubscriptionStatus.ACTIVE.name().equals(subscription.status())) {
            String next = immediate ? "CANCELED" : "CANCEL_SCHEDULED";
            updateStatus(tenantId, subscriptionId, "ACTIVE", next);
            if (immediate) {
                jdbc.update("""
                        UPDATE mb_subscription_period
                        SET status = 'ENDED', period_end = UTC_TIMESTAMP(6)
                        WHERE subscription_id = ? AND tenant_id = ? AND status = 'ACTIVE'
                        """, subscriptionId, tenantId);
            }
        } else {
            throw conflict("当前订阅状态不能取消");
        }
        audit.record(tenantId, null, "SUBSCRIPTION_CANCEL", "SUBSCRIPTION", subscriptionId.toString());
        return view(tenantId, subscriptionId);
    }

    @Transactional
    public SubscriptionVO schedulePlanChange(
            Long tenantId, Long subscriptionId, String idempotencyKey, Long targetPlanId) {
        validateIdempotencyKey(idempotencyKey);
        SubscriptionRow subscription = requireSubscription(tenantId, subscriptionId, true);
        String requestHash = hash(targetPlanId.toString());
        ChangeRow existing = findChange(tenantId, subscriptionId, idempotencyKey);
        if (existing != null) {
            requireSameRequest(existing.requestHash(), requestHash);
            return view(tenantId, subscriptionId);
        }
        if (!SubscriptionStatus.ACTIVE.name().equals(subscription.status())) {
            throw conflict("只有有效订阅可以安排套餐变更");
        }

        PeriodRow period = requireActivePeriod(tenantId, subscriptionId);
        if (subscription.planId().equals(targetPlanId)) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "目标套餐与当前套餐相同");
        }
        requireActivePlan(targetPlanId);
        requireMonthlyPrice(targetPlanId, period.periodEnd().toInstant(ZoneOffset.UTC));
        Long pending = jdbc.queryForObject("""
                SELECT COUNT(*) FROM mb_subscription_change
                WHERE subscription_id = ? AND status = 'SCHEDULED'
                """, Long.class, subscriptionId);
        if (pending != null && pending > 0) {
            throw conflict("该订阅已有待生效的套餐变更");
        }

        try {
            jdbc.update("""
                    INSERT INTO mb_subscription_change
                        (tenant_id, subscription_id, idempotency_key, request_hash,
                         from_plan_id, to_plan_id, effective_at, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'SCHEDULED')
                    """, tenantId, subscriptionId, idempotencyKey, requestHash,
                    subscription.planId(), targetPlanId, period.periodEnd());
        } catch (DuplicateKeyException ex) {
            ChangeRow raced = findChange(tenantId, subscriptionId, idempotencyKey);
            if (raced == null) {
                throw ex;
            }
            requireSameRequest(raced.requestHash(), requestHash);
        }
        audit.record(tenantId, null, "SUBSCRIPTION_PLAN_CHANGE_SCHEDULE", "SUBSCRIPTION", subscriptionId.toString());
        return view(tenantId, subscriptionId);
    }

    @Transactional
    public SubscriptionVO renew(Long tenantId, Long subscriptionId) {
        SubscriptionRow subscription = requireSubscription(tenantId, subscriptionId, true);
        if (SubscriptionStatus.EXPIRED.name().equals(subscription.status())
                || SubscriptionStatus.CANCELED.name().equals(subscription.status())) {
            return view(tenantId, subscriptionId);
        }
        if (!SubscriptionStatus.ACTIVE.name().equals(subscription.status())
                && !SubscriptionStatus.CANCEL_SCHEDULED.name().equals(subscription.status())) {
            throw conflict("当前订阅状态不能续订");
        }

        PeriodRow period = requireActivePeriod(tenantId, subscriptionId);
        LocalDateTime periodEnd = period.periodEnd();
        LocalDateTime nowUtc = LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
        if (nowUtc.isBefore(periodEnd)) {
            throw conflict("当前账期尚未结束");
        }

        if (SubscriptionStatus.CANCEL_SCHEDULED.name().equals(subscription.status())) {
            jdbc.update("""
                    UPDATE mb_subscription_period SET status = 'ENDED'
                    WHERE id = ? AND status = 'ACTIVE'
                    """, period.id());
            updateStatus(tenantId, subscriptionId, "CANCEL_SCHEDULED", "EXPIRED");
            audit.record(tenantId, null, "SUBSCRIPTION_EXPIRE", "SUBSCRIPTION", subscriptionId.toString());
            return view(tenantId, subscriptionId);
        }

        Long nextPlanId = scheduledPlanId(subscriptionId, periodEnd);
        if (nextPlanId == null) {
            nextPlanId = subscription.planId();
        }
        Instant nextStart = periodEnd.toInstant(ZoneOffset.UTC);
        ZoneId zone = tenantZone(tenantId);
        Instant nextEnd = nextStart.atZone(zone).plusMonths(1).toInstant();
        PlanPrice nextPrice = requireMonthlyPrice(nextPlanId, nextStart);
        jdbc.update("""
                UPDATE mb_subscription_period SET status = 'ENDED'
                WHERE id = ? AND status = 'ACTIVE'
                """, period.id());
        insertPeriod(tenantId, subscriptionId, nextPlanId, nextPrice,
                nextStart, nextStart, nextEnd);
        jdbc.update("""
                UPDATE mb_subscription
                SET plan_id = ?, version = version + 1
                WHERE id = ? AND tenant_id = ? AND status = 'ACTIVE'
                """, nextPlanId, subscriptionId, tenantId);
        jdbc.update("""
                UPDATE mb_subscription_change
                SET status = 'APPLIED'
                WHERE subscription_id = ? AND status = 'SCHEDULED'
                """, subscriptionId);
        audit.record(tenantId, null, "SUBSCRIPTION_RENEW", "SUBSCRIPTION", subscriptionId.toString());
        return view(tenantId, subscriptionId);
    }

    private void insertPeriod(Long tenantId, Long subscriptionId, Long planId,
                              PlanPrice price, Instant activeFrom,
                              Instant periodStart, Instant periodEnd) {
        jdbc.update("""
                INSERT INTO mb_subscription_period
                    (tenant_id, subscription_id, plan_id, price_id, price_version_id,
                     currency, base_amount_minor, active_from, period_start, period_end, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
                """, tenantId, subscriptionId, planId, price.priceId(), price.priceVersionId(),
                price.currency(), price.baseAmountMinor(), utc(activeFrom), utc(periodStart), utc(periodEnd));
    }

    private PlanPrice requireMonthlyPrice(Long planId, Instant effectiveAt) {
        List<PlanPrice> rows = jdbc.query("""
                SELECT p.id AS price_id, pv.id AS price_version_id,
                       p.currency, pv.base_amount_minor
                FROM mb_price p
                JOIN mb_price_version pv ON pv.price_id = p.id
                WHERE p.plan_id = ? AND p.billing_period = 'MONTHLY'
                  AND pv.status = 'PUBLISHED'
                  AND pv.effective_at <= ?
                ORDER BY pv.effective_at DESC
                LIMIT 1
                """, (rs, n) -> new PlanPrice(
                rs.getLong("price_id"), rs.getLong("price_version_id"),
                rs.getString("currency"), rs.getLong("base_amount_minor")),
                planId, utc(effectiveAt));
        if (rows.isEmpty()) {
            throw conflict("套餐在目标账期没有已生效的月价版本");
        }
        return rows.getFirst();
    }

    private void requireActivePlan(Long planId) {
        List<String> statuses = jdbc.query("""
                SELECT CONCAT(p.status, ':', product.status)
                FROM mb_plan p
                JOIN mb_product product ON product.id = p.product_id
                WHERE p.id = ?
                """, (rs, n) -> rs.getString(1), planId);
        if (statuses.isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "套餐不存在");
        }
        if (!"ACTIVE:ACTIVE".equals(statuses.getFirst())) {
            throw conflict("只有已发布产品中的有效套餐可以订阅");
        }
    }

    private ZoneId tenantZone(Long tenantId) {
        List<String> zones = jdbc.query("""
                SELECT billing_zone_id FROM mb_tenant WHERE id = ? AND status = 'ACTIVE'
                """, (rs, n) -> rs.getString(1), tenantId);
        if (zones.isEmpty()) {
            throw new BusinessException(ErrorCode.TENANT_FORBIDDEN);
        }
        try {
            return ZoneId.of(zones.getFirst());
        } catch (RuntimeException ex) {
            throw conflict("租户账期时区配置无效");
        }
    }

    private SubscriptionRow findByIdempotencyKey(Long tenantId, String key) {
        List<SubscriptionRow> rows = jdbc.query("""
                SELECT id, plan_id, status, request_hash
                FROM mb_subscription WHERE tenant_id = ? AND idempotency_key = ?
                """, (rs, n) -> new SubscriptionRow(rs.getLong("id"), tenantId,
                rs.getLong("plan_id"), rs.getString("status"), rs.getString("request_hash")),
                tenantId, key);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private ChangeRow findChange(Long tenantId, Long subscriptionId, String key) {
        List<ChangeRow> rows = jdbc.query("""
                SELECT request_hash FROM mb_subscription_change
                WHERE tenant_id = ? AND subscription_id = ? AND idempotency_key = ?
                """, (rs, n) -> new ChangeRow(rs.getString("request_hash")),
                tenantId, subscriptionId, key);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Long scheduledPlanId(Long subscriptionId, LocalDateTime effectiveAt) {
        List<Long> rows = jdbc.query("""
                SELECT to_plan_id FROM mb_subscription_change
                WHERE subscription_id = ? AND status = 'SCHEDULED'
                  AND effective_at <= ?
                ORDER BY id DESC LIMIT 1
                """, (rs, n) -> rs.getLong(1), subscriptionId, effectiveAt);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private SubscriptionRow requireSubscription(Long tenantId, Long id, boolean lock) {
        String sql = """
                SELECT id, tenant_id, plan_id, status, request_hash
                FROM mb_subscription WHERE tenant_id = ? AND id = ?
                """ + (lock ? " FOR UPDATE" : "");
        List<SubscriptionRow> rows = jdbc.query(sql,
                (rs, n) -> new SubscriptionRow(rs.getLong("id"), rs.getLong("tenant_id"),
                        rs.getLong("plan_id"), rs.getString("status"), rs.getString("request_hash")),
                tenantId, id);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "订阅不存在");
        }
        return rows.getFirst();
    }

    private PeriodRow requireActivePeriod(Long tenantId, Long subscriptionId) {
        List<PeriodRow> rows = jdbc.query("""
                SELECT id, period_start, period_end FROM mb_subscription_period
                WHERE tenant_id = ? AND subscription_id = ? AND status = 'ACTIVE'
                FOR UPDATE
                """, (rs, n) -> new PeriodRow(rs.getLong("id"),
                rs.getObject("period_start", LocalDateTime.class),
                rs.getObject("period_end", LocalDateTime.class)), tenantId, subscriptionId);
        if (rows.isEmpty()) {
            throw conflict("订阅没有有效账期");
        }
        return rows.getFirst();
    }

    private SubscriptionVO view(Long tenantId, Long id) {
        List<SubscriptionVO> rows = jdbc.query("""
                SELECT s.id, s.tenant_id, s.plan_id, s.status,
                       p.price_version_id, p.currency, p.base_amount_minor, p.id AS period_id,
                       p.active_from, p.period_start, p.period_end
                FROM mb_subscription s
                LEFT JOIN mb_subscription_period p
                  ON p.id = (
                    SELECT MAX(latest.id)
                    FROM mb_subscription_period latest
                    WHERE latest.subscription_id = s.id AND latest.tenant_id = s.tenant_id
                  )
                WHERE s.tenant_id = ? AND s.id = ?
                """, (rs, n) -> new SubscriptionVO(
                rs.getLong("id"), rs.getLong("tenant_id"), rs.getLong("plan_id"),
                rs.getString("status"), (Long) rs.getObject("price_version_id"),
                rs.getString("currency"), (Long) rs.getObject("base_amount_minor"),
                instant(rs.getObject("active_from", LocalDateTime.class)),
                (Long) rs.getObject("period_id"),
                instant(rs.getObject("period_start", LocalDateTime.class)),
                instant(rs.getObject("period_end", LocalDateTime.class))), tenantId, id);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "订阅不存在");
        }
        return rows.getFirst();
    }

    private void updateStatus(Long tenantId, Long id, String from, String to) {
        int changed = jdbc.update("""
                UPDATE mb_subscription SET status = ?, version = version + 1
                WHERE id = ? AND tenant_id = ? AND status = ?
                """, to, id, tenantId, from);
        if (changed != 1) {
            throw conflict("订阅状态已变化，请刷新后重试");
        }
    }

    private void requireSameRequest(String existingHash, String requestHash) {
        if (!existingHash.equals(requestHash)) {
            throw conflict("幂等键已用于不同请求");
        }
    }

    private void validateIdempotencyKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "Idempotency-Key 必须为 1 到 128 个字符");
        }
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.BUSINESS_CONFLICT, message);
    }

    private record SubscriptionRow(
            Long id, Long tenantId, Long planId, String status, String requestHash) {
    }

    private record ChangeRow(String requestHash) {
    }

    private record PeriodRow(Long id, LocalDateTime periodStart, LocalDateTime periodEnd) {
    }

    private record PlanPrice(Long priceId, Long priceVersionId,
                             String currency, Long baseAmountMinor) {
    }
}
