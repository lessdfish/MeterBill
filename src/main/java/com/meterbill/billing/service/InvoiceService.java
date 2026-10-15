package com.meterbill.billing.service;

import com.meterbill.common.exception.BusinessException;
import com.meterbill.common.exception.ErrorCode;
import com.meterbill.product.domain.PricingMode;
import com.meterbill.product.domain.UsageChargeCalculator;
import com.meterbill.billing.web.vo.InvoiceAdjustmentVO;
import com.meterbill.billing.web.vo.InvoiceLineVO;
import com.meterbill.billing.web.vo.InvoiceVO;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class InvoiceService {

    private final JdbcTemplate jdbc;
    private final InvoiceAccountingService accounting;

    @Transactional(readOnly = true)
    public List<Long> findDueUnbilledPeriodIds(int limit) {
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("limit must be between 1 and 500");
        }
        return jdbc.query("""
                SELECT sp.id
                FROM mb_subscription_period sp
                LEFT JOIN mb_invoice i ON i.period_id = sp.id
                WHERE sp.period_end <= UTC_TIMESTAMP(6) AND i.id IS NULL
                ORDER BY sp.period_end, sp.id
                LIMIT ?
                """, (rs, row) -> rs.getLong(1), limit);
    }

    @Transactional
    public InvoiceVO issuePeriod(Long periodId) {
        BillingPeriod period = requirePeriod(periodId, true);
        InvoiceRow existing = findInvoice(period.id());
        if (existing != null) {
            accounting.synchronize(period.tenantId(), existing.id());
            return getInvoice(period.tenantId(), existing.id());
        }
        if (period.periodEnd().isAfter(LocalDateTime.now(ZoneOffset.UTC))) {
            throw conflict("账期尚未结束，不能出账");
        }
        requireAllEventsProcessed(period);

        Map<Long, Long> totals = periodTotals(period.id());
        Map<Long, Long> eventTotals = processedEventTotals(period.id());
        if (!totals.equals(eventTotals)) {
            throw conflict("计量聚合与已处理事件不一致，暂不能出账");
        }
        Map<Long, PricingRule> rules = loadRules(period.priceVersionId());
        for (Long meterId : totals.keySet()) {
            if (!rules.containsKey(meterId)) {
                throw conflict("价格版本缺少已使用计量项的计价规则");
            }
        }

        List<RatedLine> ratedLines = new ArrayList<>();
        long usageAmount = 0;
        int lineNo = 2;
        for (PricingRule rule : rules.values()) {
            long quantity = totals.getOrDefault(rule.meterId(), 0L);
            UsageChargeCalculator.Charge charge = calculate(rule, quantity);
            usageAmount = addExact(usageAmount, charge.usageAmountMinor(), "账单金额超出 BIGINT 范围");
            ratedLines.add(new RatedLine(lineNo++, rule, quantity,
                    charge.billableQuantity(), charge.usageAmountMinor()));
        }
        long invoiceTotal = addExact(period.baseAmountMinor(), usageAmount,
                "账单金额超出 BIGINT 范围");

        jdbc.update("""
                INSERT INTO mb_invoice
                    (tenant_id, subscription_id, period_id, price_version_id, currency,
                     period_start, period_end, base_amount_minor, usage_amount_minor,
                     total_amount_minor, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ISSUED')
                """, period.tenantId(), period.subscriptionId(), period.id(),
                period.priceVersionId(), period.currency(), period.periodStart(), period.periodEnd(),
                period.baseAmountMinor(), usageAmount, invoiceTotal);
        Long invoiceId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        jdbc.update("""
                INSERT INTO mb_invoice_line
                    (invoice_id, line_no, line_type, description, quantity,
                     included_quantity, billable_quantity, amount_minor, rate_snapshot)
                VALUES (?, 1, 'BASE', 'Monthly subscription fee', 0, 0, 0, ?, ?)
                """, invoiceId, period.baseAmountMinor(),
                "priceVersionId=" + period.priceVersionId()
                        + ";baseAmountMinor=" + period.baseAmountMinor());
        for (RatedLine line : ratedLines) {
            jdbc.update("""
                    INSERT INTO mb_invoice_line
                        (invoice_id, line_no, line_type, meter_id, description,
                         pricing_mode, quantity, included_quantity, billable_quantity,
                         amount_minor, rate_snapshot)
                    VALUES (?, ?, 'USAGE', ?, ?, ?, ?, ?, ?, ?, ?)
                    """, invoiceId, line.lineNo(), line.rule().meterId(),
                    "Usage: " + line.rule().meterCode(), line.rule().mode().name(),
                    line.quantity(), line.rule().includedQuantity(), line.billableQuantity(),
                    line.amountMinor(), line.rule().snapshot(period.priceVersionId()));
        }
        jdbc.update("""
                INSERT INTO mb_invoice_event (usage_event_id, invoice_id)
                SELECT e.id, ? FROM mb_usage_event e
                WHERE e.period_id = ? AND e.status = 'PROCESSED'
                  AND NOT EXISTS (
                      SELECT 1 FROM mb_invoice_event ie WHERE ie.usage_event_id = e.id
                  )
                """, invoiceId, period.id());
        accounting.synchronize(period.tenantId(), invoiceId);
        return getInvoice(period.tenantId(), invoiceId);
    }

    @Transactional
    public InvoiceAdjustmentVO adjustLateUsage(Long periodId) {
        BillingPeriod period = requirePeriod(periodId, true);
        InvoiceRow invoice = findInvoice(period.id());
        if (invoice == null) {
            throw conflict("该账期尚未正式出账");
        }
        if ("VOIDED".equals(invoice.status())) {
            throw conflict("已作废账单不能生成迟到用量调整");
        }
        jdbc.queryForObject("SELECT id FROM mb_invoice WHERE id = ? FOR UPDATE", Long.class, invoice.id());
        requireAllEventsProcessed(period);

        List<UnassignedEvent> lateEvents = jdbc.query("""
                SELECT e.id, e.meter_id, e.quantity
                FROM mb_usage_event e
                LEFT JOIN mb_invoice_event ie ON ie.usage_event_id = e.id
                WHERE e.period_id = ? AND e.status = 'PROCESSED'
                  AND ie.usage_event_id IS NULL
                ORDER BY e.occurred_at, e.id
                FOR UPDATE
                """, (rs, row) -> new UnassignedEvent(
                rs.getLong("id"), rs.getLong("meter_id"), rs.getLong("quantity")), period.id());
        if (lateEvents.isEmpty()) {
            throw conflict("没有待处理的迟到用量事件");
        }

        Map<Long, PricingRule> rules = loadRules(period.priceVersionId());
        Map<Long, BilledMeterState> states = billedStates(invoice.id());
        Map<Long, AdjustmentLine> lines = new LinkedHashMap<>();
        for (UnassignedEvent event : lateEvents) {
            PricingRule rule = rules.get(event.meterId());
            if (rule == null) {
                throw conflict("价格版本缺少迟到事件计量项的计价规则");
            }
            BilledMeterState state = states.computeIfAbsent(
                    event.meterId(), ignored -> new BilledMeterState());
            long beforeQuantity = state.quantity;
            long beforeAmount = state.amount;
            long beforeBillable = state.billableQuantity;
            state.quantity = addExact(state.quantity, event.quantity(), "账期用量超出 BIGINT 范围");
            UsageChargeCalculator.Charge recalculated = calculate(rule, state.quantity);
            long amountDelta = subtractExact(recalculated.usageAmountMinor(), beforeAmount,
                    "迟到用量调整金额超出 BIGINT 范围");
            long billableDelta = subtractExact(recalculated.billableQuantity(), beforeBillable,
                    "迟到用量计费数量异常");
            state.amount = recalculated.usageAmountMinor();
            state.billableQuantity = recalculated.billableQuantity();

            AdjustmentLine line = lines.computeIfAbsent(event.meterId(),
                    ignored -> new AdjustmentLine(rule));
            line.quantity = addExact(line.quantity, event.quantity(), "调整数量超出 BIGINT 范围");
            line.billableQuantity = addExact(line.billableQuantity, billableDelta,
                    "调整计费数量超出 BIGINT 范围");
            line.amountMinor = addExact(line.amountMinor, amountDelta,
                    "调整金额超出 BIGINT 范围");
            line.snapshot = rule.snapshot(period.priceVersionId())
                    + ";previousQuantity=" + beforeQuantity
                    + ";previousAmountMinor=" + beforeAmount
                    + ";newQuantity=" + state.quantity
                    + ";newAmountMinor=" + state.amount;
        }

        int adjustmentNo = jdbc.queryForObject("""
                SELECT COALESCE(MAX(adjustment_no), 0) + 1
                FROM mb_invoice_adjustment WHERE invoice_id = ?
                """, Integer.class, invoice.id());
        long adjustmentAmount = 0;
        for (AdjustmentLine line : lines.values()) {
            adjustmentAmount = addExact(adjustmentAmount, line.amountMinor,
                    "调整总金额超出 BIGINT 范围");
        }
        jdbc.update("""
                INSERT INTO mb_invoice_adjustment
                    (tenant_id, invoice_id, period_id, adjustment_no, currency,
                     amount_minor, source_event_count, reason, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'LATE_USAGE', 'ISSUED')
                """, period.tenantId(), invoice.id(), period.id(), adjustmentNo,
                period.currency(), adjustmentAmount, lateEvents.size());
        Long adjustmentId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        int lineNo = 1;
        for (Map.Entry<Long, AdjustmentLine> entry : lines.entrySet()) {
            AdjustmentLine line = entry.getValue();
            jdbc.update("""
                    INSERT INTO mb_invoice_adjustment_line
                        (adjustment_id, line_no, meter_id, description, pricing_mode,
                         quantity, included_quantity, billable_quantity, amount_minor, rate_snapshot)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, adjustmentId, lineNo++, entry.getKey(),
                    "Late usage: " + line.rule.meterCode(), line.rule.mode().name(),
                    line.quantity, line.rule.includedQuantity(), line.billableQuantity,
                    line.amountMinor, line.snapshot);
        }
        for (UnassignedEvent event : lateEvents) {
            jdbc.update("""
                    INSERT INTO mb_invoice_event (usage_event_id, adjustment_id)
                    VALUES (?, ?)
                    """, event.id(), adjustmentId);
        }
        accounting.synchronize(period.tenantId(), invoice.id());
        return getAdjustment(period.tenantId(), adjustmentId);
    }

    @Transactional(readOnly = true)
    public InvoiceVO getInvoice(Long tenantId, Long invoiceId) {
        List<InvoiceRow> invoices = jdbc.query("""
                SELECT id, tenant_id, subscription_id, period_id, price_version_id,
                       currency, period_start, period_end, base_amount_minor,
                       usage_amount_minor, total_amount_minor, status, issued_at
                FROM mb_invoice WHERE tenant_id = ? AND id = ?
                """, (rs, row) -> new InvoiceRow(
                rs.getLong("id"), rs.getLong("tenant_id"), rs.getLong("subscription_id"),
                rs.getLong("period_id"), rs.getLong("price_version_id"), rs.getString("currency"),
                rs.getObject("period_start", LocalDateTime.class),
                rs.getObject("period_end", LocalDateTime.class), rs.getLong("base_amount_minor"),
                rs.getLong("usage_amount_minor"), rs.getLong("total_amount_minor"),
                rs.getString("status"), rs.getObject("issued_at", LocalDateTime.class)), tenantId, invoiceId);
        if (invoices.isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "账单不存在");
        }
        InvoiceRow invoice = invoices.getFirst();
        List<InvoiceLineVO> lines = invoiceLines(invoiceId);
        List<Long> adjustmentIds = jdbc.query("""
                SELECT id FROM mb_invoice_adjustment WHERE invoice_id = ? ORDER BY adjustment_no
                """, (rs, row) -> rs.getLong(1), invoiceId);
        List<InvoiceAdjustmentVO> adjustments = adjustmentIds.stream()
                .map(id -> getAdjustment(tenantId, id)).toList();
        return new InvoiceVO(invoice.id(), invoice.tenantId(), invoice.subscriptionId(),
                invoice.periodId(), invoice.priceVersionId(), invoice.currency(),
                instant(invoice.periodStart()), instant(invoice.periodEnd()),
                invoice.baseAmountMinor(), invoice.usageAmountMinor(), invoice.totalAmountMinor(),
                invoice.status(), instant(invoice.issuedAt()), lines, adjustments);
    }

    private InvoiceAdjustmentVO getAdjustment(Long tenantId, Long adjustmentId) {
        List<AdjustmentHeader> rows = jdbc.query("""
                SELECT id, invoice_id, period_id, adjustment_no, currency, amount_minor,
                       source_event_count, reason, status, created_at
                FROM mb_invoice_adjustment WHERE tenant_id = ? AND id = ?
                """, (rs, row) -> new AdjustmentHeader(
                rs.getLong("id"), rs.getLong("invoice_id"), rs.getLong("period_id"),
                rs.getInt("adjustment_no"), rs.getString("currency"), rs.getLong("amount_minor"),
                rs.getInt("source_event_count"), rs.getString("reason"), rs.getString("status"),
                instant(rs.getObject("created_at", LocalDateTime.class))), tenantId, adjustmentId);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "账单调整不存在");
        }
        AdjustmentHeader header = rows.getFirst();
        return new InvoiceAdjustmentVO(header.id(), header.invoiceId(), header.periodId(),
                header.adjustmentNo(), header.currency(), header.amountMinor(),
                header.sourceEventCount(), header.reason(), header.status(), header.createdAt(),
                adjustmentLines(adjustmentId));
    }

    private List<InvoiceLineVO> invoiceLines(Long invoiceId) {
        return jdbc.query("""
                SELECT id, line_no, line_type, meter_id, description, pricing_mode,
                       quantity, included_quantity, billable_quantity, amount_minor, rate_snapshot
                FROM mb_invoice_line WHERE invoice_id = ? ORDER BY line_no
                """, (rs, row) -> new InvoiceLineVO(
                rs.getLong("id"), rs.getInt("line_no"), rs.getString("line_type"),
                rs.getObject("meter_id", Long.class), rs.getString("description"),
                rs.getString("pricing_mode"), rs.getLong("quantity"),
                rs.getLong("included_quantity"), rs.getLong("billable_quantity"),
                rs.getLong("amount_minor"), rs.getString("rate_snapshot")), invoiceId);
    }

    private List<InvoiceLineVO> adjustmentLines(Long adjustmentId) {
        return jdbc.query("""
                SELECT id, line_no, meter_id, description, pricing_mode,
                       quantity, included_quantity, billable_quantity, amount_minor, rate_snapshot
                FROM mb_invoice_adjustment_line WHERE adjustment_id = ? ORDER BY line_no
                """, (rs, row) -> new InvoiceLineVO(
                rs.getLong("id"), rs.getInt("line_no"), "USAGE",
                rs.getLong("meter_id"), rs.getString("description"),
                rs.getString("pricing_mode"), rs.getLong("quantity"),
                rs.getLong("included_quantity"), rs.getLong("billable_quantity"),
                rs.getLong("amount_minor"), rs.getString("rate_snapshot")), adjustmentId);
    }

    private BillingPeriod requirePeriod(Long periodId, boolean lock) {
        String sql = """
                SELECT sp.id, sp.tenant_id, sp.subscription_id, sp.price_version_id,
                       sp.currency, sp.base_amount_minor, sp.period_start, sp.period_end
                FROM mb_subscription_period sp WHERE sp.id = ?
                """ + (lock ? " FOR UPDATE" : "");
        List<BillingPeriod> rows = jdbc.query(sql, (rs, row) -> new BillingPeriod(
                rs.getLong("id"), rs.getLong("tenant_id"), rs.getLong("subscription_id"),
                rs.getLong("price_version_id"), rs.getString("currency"),
                rs.getLong("base_amount_minor"), rs.getObject("period_start", LocalDateTime.class),
                rs.getObject("period_end", LocalDateTime.class)), periodId);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "订阅账期不存在");
        }
        return rows.getFirst();
    }

    private InvoiceRow findInvoice(Long periodId) {
        List<InvoiceRow> rows = jdbc.query("""
                SELECT id, tenant_id, subscription_id, period_id, price_version_id,
                       currency, period_start, period_end, base_amount_minor,
                       usage_amount_minor, total_amount_minor, status, issued_at
                FROM mb_invoice WHERE period_id = ?
                """, (rs, row) -> new InvoiceRow(
                rs.getLong("id"), rs.getLong("tenant_id"), rs.getLong("subscription_id"),
                rs.getLong("period_id"), rs.getLong("price_version_id"), rs.getString("currency"),
                rs.getObject("period_start", LocalDateTime.class),
                rs.getObject("period_end", LocalDateTime.class), rs.getLong("base_amount_minor"),
                rs.getLong("usage_amount_minor"), rs.getLong("total_amount_minor"),
                rs.getString("status"), rs.getObject("issued_at", LocalDateTime.class)), periodId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void requireAllEventsProcessed(BillingPeriod period) {
        Integer pending = jdbc.queryForObject("""
                SELECT COUNT(*) FROM mb_usage_event
                WHERE tenant_id = ? AND subscription_id = ? AND period_id = ?
                  AND status <> 'PROCESSED'
                """, Integer.class, period.tenantId(), period.subscriptionId(), period.id());
        if (pending != null && pending > 0) {
            throw conflict("该账期仍有未处理或失败的用量事件");
        }
    }

    private Map<Long, Long> periodTotals(Long periodId) {
        Map<Long, Long> totals = new LinkedHashMap<>();
        jdbc.query("""
                SELECT meter_id, total_quantity FROM mb_usage_period_total
                WHERE period_id = ? ORDER BY meter_id
                """, rs -> {
            totals.put(rs.getLong("meter_id"), rs.getLong("total_quantity"));
        }, periodId);
        return totals;
    }

    private Map<Long, Long> processedEventTotals(Long periodId) {
        Map<Long, Long> totals = new LinkedHashMap<>();
        jdbc.query("""
                SELECT meter_id, SUM(quantity) AS total_quantity
                FROM mb_usage_event WHERE period_id = ? AND status = 'PROCESSED'
                GROUP BY meter_id ORDER BY meter_id
                """, rs -> {
            totals.put(rs.getLong("meter_id"), rs.getLong("total_quantity"));
        }, periodId);
        return totals;
    }

    private Map<Long, PricingRule> loadRules(Long priceVersionId) {
        List<RuleHeader> headers = jdbc.query("""
                SELECT r.id, r.meter_id, m.meter_code, r.pricing_mode,
                       r.included_quantity, r.unit_amount_minor
                FROM mb_usage_price_rule r
                JOIN mb_usage_meter m ON m.id = r.meter_id
                WHERE r.price_version_id = ? ORDER BY r.meter_id
                """, (rs, row) -> new RuleHeader(
                rs.getLong("id"), rs.getLong("meter_id"), rs.getString("meter_code"),
                PricingMode.valueOf(rs.getString("pricing_mode")),
                rs.getLong("included_quantity"), rs.getObject("unit_amount_minor", Long.class)),
                priceVersionId);
        Map<Long, PricingRule> rules = new LinkedHashMap<>();
        for (RuleHeader header : headers) {
            List<UsageChargeCalculator.Tier> tiers = jdbc.query("""
                    SELECT up_to_quantity, unit_amount_minor FROM mb_usage_price_tier
                    WHERE rule_id = ? ORDER BY tier_no
                    """, (rs, row) -> new UsageChargeCalculator.Tier(
                    rs.getObject("up_to_quantity", Long.class), rs.getLong("unit_amount_minor")),
                    header.id());
            rules.put(header.meterId(), new PricingRule(header.id(), header.meterId(),
                    header.meterCode(), header.mode(), header.includedQuantity(),
                    header.unitAmountMinor(), tiers));
        }
        return rules;
    }

    private Map<Long, BilledMeterState> billedStates(Long invoiceId) {
        Map<Long, BilledMeterState> states = new LinkedHashMap<>();
        jdbc.query("""
                SELECT meter_id, SUM(quantity) AS quantity,
                       SUM(billable_quantity) AS billable_quantity,
                       SUM(amount_minor) AS amount_minor
                FROM (
                    SELECT l.meter_id, l.quantity, l.billable_quantity, l.amount_minor
                    FROM mb_invoice_line l
                    WHERE l.invoice_id = ? AND l.line_type = 'USAGE'
                    UNION ALL
                    SELECT l.meter_id, l.quantity, l.billable_quantity, l.amount_minor
                    FROM mb_invoice_adjustment_line l
                    JOIN mb_invoice_adjustment a ON a.id = l.adjustment_id
                    WHERE a.invoice_id = ? AND a.status <> 'VOIDED'
                ) billed
                GROUP BY meter_id
                """, rs -> {
            BilledMeterState state = new BilledMeterState();
            state.quantity = rs.getLong("quantity");
            state.billableQuantity = rs.getLong("billable_quantity");
            state.amount = rs.getLong("amount_minor");
            states.put(rs.getLong("meter_id"), state);
        }, invoiceId, invoiceId);
        return states;
    }

    private UsageChargeCalculator.Charge calculate(PricingRule rule, long quantity) {
        try {
            return UsageChargeCalculator.calculate(rule.mode(), quantity,
                    rule.includedQuantity(), rule.unitAmountMinor(), rule.tiers());
        } catch (ArithmeticException | IllegalArgumentException | IllegalStateException ex) {
            throw conflict("价格版本中的用量规则无法确定性计价: " + ex.getMessage());
        }
    }

    private long addExact(long first, long second, String message) {
        try {
            return Math.addExact(first, second);
        } catch (ArithmeticException ex) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, message);
        }
    }

    private long subtractExact(long first, long second, String message) {
        try {
            return Math.subtractExact(first, second);
        } catch (ArithmeticException ex) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, message);
        }
    }

    private Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.BUSINESS_CONFLICT, message);
    }

    private record BillingPeriod(Long id, Long tenantId, Long subscriptionId,
                                 Long priceVersionId, String currency, Long baseAmountMinor,
                                 LocalDateTime periodStart, LocalDateTime periodEnd) {
    }

    private record InvoiceRow(Long id, Long tenantId, Long subscriptionId, Long periodId,
                              Long priceVersionId, String currency, LocalDateTime periodStart,
                              LocalDateTime periodEnd, Long baseAmountMinor, Long usageAmountMinor,
                              Long totalAmountMinor, String status, LocalDateTime issuedAt) {
    }

    private record AdjustmentHeader(Long id, Long invoiceId, Long periodId, Integer adjustmentNo,
                                    String currency, Long amountMinor, Integer sourceEventCount,
                                    String reason, String status, Instant createdAt) {
    }

    private record RuleHeader(Long id, Long meterId, String meterCode, PricingMode mode,
                              Long includedQuantity, Long unitAmountMinor) {
    }

    private record PricingRule(Long id, Long meterId, String meterCode, PricingMode mode,
                               Long includedQuantity, Long unitAmountMinor,
                               List<UsageChargeCalculator.Tier> tiers) {
        private String snapshot(Long priceVersionId) {
            String tierSnapshot = tiers.stream()
                    .map(tier -> String.valueOf(tier.upToQuantity()) + ":" + tier.unitAmountMinor())
                    .reduce((first, second) -> first + "," + second).orElse("");
            return "priceVersionId=" + priceVersionId + ";ruleId=" + id
                    + ";mode=" + mode + ";includedQuantity=" + includedQuantity
                    + ";unitAmountMinor=" + unitAmountMinor + ";tiers=[" + tierSnapshot + "]";
        }
    }

    private record RatedLine(int lineNo, PricingRule rule, long quantity,
                             long billableQuantity, long amountMinor) {
    }

    private record UnassignedEvent(Long id, Long meterId, Long quantity) {
    }

    private static final class BilledMeterState {
        private long quantity;
        private long billableQuantity;
        private long amount;
    }

    private static final class AdjustmentLine {
        private final PricingRule rule;
        private long quantity;
        private long billableQuantity;
        private long amountMinor;
        private String snapshot;

        private AdjustmentLine(PricingRule rule) {
            this.rule = rule;
        }
    }
}
