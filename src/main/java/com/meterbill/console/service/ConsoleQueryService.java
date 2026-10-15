package com.meterbill.console.service;

import com.meterbill.common.api.PageResponse;
import com.meterbill.common.exception.BusinessException;
import com.meterbill.common.exception.ErrorCode;
import com.meterbill.console.web.vo.ConsoleQueryModels.ChannelFact;
import com.meterbill.console.web.vo.ConsoleQueryModels.CustomerRow;
import com.meterbill.console.web.vo.ConsoleQueryModels.DashboardOverview;
import com.meterbill.console.web.vo.ConsoleQueryModels.InvoiceRow;
import com.meterbill.console.web.vo.ConsoleQueryModels.LedgerEntryRow;
import com.meterbill.console.web.vo.ConsoleQueryModels.AvailablePlan;
import com.meterbill.console.web.vo.ConsoleQueryModels.MemberRow;
import com.meterbill.console.web.vo.ConsoleQueryModels.OperationTask;
import com.meterbill.console.web.vo.ConsoleQueryModels.PaymentInvestigation;
import com.meterbill.console.web.vo.ConsoleQueryModels.PaymentRow;
import com.meterbill.console.web.vo.ConsoleQueryModels.ReconciliationRow;
import com.meterbill.console.web.vo.ConsoleQueryModels.RefundRow;
import com.meterbill.console.web.vo.ConsoleQueryModels.SubscriptionRow;
import com.meterbill.console.web.vo.ConsoleQueryModels.TimelineEvent;
import com.meterbill.console.web.vo.ConsoleQueryModels.UsageEventRow;
import com.meterbill.console.web.vo.ConsoleQueryModels.UsagePoint;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ConsoleQueryService {

    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public PageResponse<CustomerRow> customers(long page, long size, String status, String keyword) {
        PageSpec spec = page(page, size);
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (text(status)) {
            where.append(" AND t.status = ?");
            args.add(status.trim().toUpperCase());
        }
        if (text(keyword)) {
            where.append(" AND (t.name LIKE ? OR t.tenant_code LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
        }
        long total = count("SELECT COUNT(*) FROM mb_tenant t" + where, args);
        List<Object> paged = new ArrayList<>(args);
        paged.add(spec.size());
        paged.add(spec.offset());
        List<CustomerRow> rows = jdbc.query("""
                SELECT t.id, t.tenant_code, t.name, t.status, t.created_at,
                       (SELECT COUNT(*) FROM mb_tenant_member m
                        WHERE m.tenant_id = t.id AND m.status = 'ACTIVE') member_count,
                       (SELECT COUNT(*) FROM mb_subscription s
                        WHERE s.tenant_id = t.id AND s.status IN ('ACTIVE','CANCEL_SCHEDULED')) active_subscription_count,
                       (SELECT COUNT(*) FROM mb_invoice i
                        WHERE i.tenant_id = t.id AND i.status <> 'VOIDED') invoice_count,
                       COALESCE((SELECT SUM(i.total_amount_minor) FROM mb_invoice i
                                 WHERE i.tenant_id = t.id AND i.status NOT IN ('PAID','VOIDED')), 0)
                         - COALESCE((SELECT SUM(p.amount_minor) FROM mb_payment p
                                     JOIN mb_invoice pi ON pi.id = p.invoice_id
                                     WHERE p.tenant_id = t.id AND p.status = 'SUCCEEDED'
                                       AND pi.status NOT IN ('PAID','VOIDED')), 0)
                         + COALESCE((SELECT SUM(r.amount_minor) FROM mb_refund r
                                     JOIN mb_payment rp ON rp.id = r.payment_id
                                     JOIN mb_invoice ri ON ri.id = rp.invoice_id
                                     WHERE r.tenant_id = t.id AND r.status = 'SUCCEEDED'
                                       AND ri.status NOT IN ('PAID','VOIDED')), 0) outstanding_minor,
                       COALESCE((SELECT MAX(i.currency) FROM mb_invoice i
                                 WHERE i.tenant_id = t.id), 'CNY') currency
                FROM mb_tenant t
                """ + where + " ORDER BY t.id DESC LIMIT ? OFFSET ?",
                (rs, row) -> new CustomerRow(
                        rs.getLong("id"), rs.getString("tenant_code"), rs.getString("name"),
                        rs.getString("status"), rs.getLong("member_count"),
                        rs.getLong("active_subscription_count"), rs.getLong("invoice_count"),
                        Math.max(0, rs.getLong("outstanding_minor")), rs.getString("currency"),
                        instant(rs, "created_at")), paged.toArray());
        return new PageResponse<>(spec.page(), spec.size(), total, rows);
    }

    @Transactional(readOnly = true)
    public DashboardOverview dashboard(Long tenantId) {
        MoneyTotals invoice = jdbc.queryForObject("""
                SELECT COALESCE(MAX(i.currency), 'CNY') currency,
                       COALESCE(SUM(i.total_amount_minor), 0)
                         + COALESCE((SELECT SUM(a.amount_minor)
                                     FROM mb_invoice_adjustment a
                                     WHERE a.tenant_id = ? AND a.status <> 'VOIDED'), 0) receivable,
                       COUNT(CASE WHEN i.status <> 'PAID' AND i.status <> 'VOIDED' THEN 1 END) pending_invoices
                FROM mb_invoice i
                WHERE i.tenant_id = ? AND i.status <> 'VOIDED'
                """, (rs, row) -> new MoneyTotals(
                rs.getString("currency"), rs.getLong("receivable"),
                rs.getLong("pending_invoices")), tenantId, tenantId);
        long paid = scalar("""
                SELECT COALESCE(SUM(CASE WHEN p.status = 'SUCCEEDED' THEN p.amount_minor ELSE 0 END), 0)
                     - COALESCE((SELECT SUM(r.amount_minor) FROM mb_refund r
                                 WHERE r.tenant_id = ? AND r.status = 'SUCCEEDED'), 0)
                FROM mb_payment p WHERE p.tenant_id = ?
                """, tenantId, tenantId);
        long unknown = scalar("SELECT COUNT(*) FROM mb_payment WHERE tenant_id = ? AND status = 'UNKNOWN'", tenantId);
        long failedPayments = scalar("SELECT COUNT(*) FROM mb_payment WHERE tenant_id = ? AND status = 'FAILED'", tenantId);
        long failedEvents = scalar("SELECT COUNT(*) FROM mb_usage_event WHERE tenant_id = ? AND status IN ('RETRYABLE_FAILED','FINAL_FAILED')", tenantId);
        long differences = scalar("SELECT COUNT(*) FROM mb_reconciliation_difference WHERE tenant_id = ? AND status IN ('OPEN','INVESTIGATING')", tenantId);
        List<UsagePoint> trend = jdbc.query("""
                SELECT DATE(occurred_at) usage_date, SUM(quantity) quantity, COUNT(*) event_count
                FROM mb_usage_event
                WHERE tenant_id = ? AND occurred_at >= UTC_TIMESTAMP() - INTERVAL 6 DAY
                GROUP BY DATE(occurred_at)
                ORDER BY usage_date
                """, (rs, row) -> new UsagePoint(
                rs.getDate("usage_date").toLocalDate(), rs.getLong("quantity"),
                rs.getLong("event_count")), tenantId);
        long receivable = invoice == null ? 0 : invoice.receivable();
        return new DashboardOverview(
                invoice == null ? "CNY" : invoice.currency(), receivable, paid,
                Math.max(0, receivable - paid), unknown, failedPayments, failedEvents,
                differences, invoice == null ? 0 : invoice.pendingInvoices(), trend);
    }

    @Transactional(readOnly = true)
    public PageResponse<InvoiceRow> invoices(Long tenantId, long page, long size,
                                             String status, String keyword) {
        PageSpec spec = page(page, size);
        StringBuilder where = new StringBuilder(" WHERE i.tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (text(status)) {
            where.append(" AND i.status = ?");
            args.add(status.trim().toUpperCase());
        }
        if (text(keyword)) {
            where.append(" AND (CAST(i.id AS CHAR) LIKE ? OR CAST(i.subscription_id AS CHAR) LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
        }
        long total = count("SELECT COUNT(*) FROM mb_invoice i" + where, args);
        List<Object> paged = new ArrayList<>(args);
        paged.add(spec.size());
        paged.add(spec.offset());
        List<InvoiceRow> rows = jdbc.query("""
                SELECT i.id, i.subscription_id, t.name tenant_name, i.currency,
                       i.period_start, i.period_end, i.total_amount_minor, i.status, i.issued_at,
                       COALESCE((SELECT SUM(p.amount_minor) FROM mb_payment p
                                 WHERE p.tenant_id = i.tenant_id AND p.invoice_id = i.id
                                   AND p.status = 'SUCCEEDED'), 0)
                       - COALESCE((SELECT SUM(r.amount_minor) FROM mb_refund r
                                  JOIN mb_payment p2 ON p2.id = r.payment_id
                                  WHERE r.tenant_id = i.tenant_id AND p2.invoice_id = i.id
                                    AND r.status = 'SUCCEEDED'), 0) paid_amount_minor
                FROM mb_invoice i
                JOIN mb_tenant t ON t.id = i.tenant_id
                """ + where + " ORDER BY i.id DESC LIMIT ? OFFSET ?",
                (rs, row) -> new InvoiceRow(
                        rs.getLong("id"), rs.getLong("subscription_id"),
                        rs.getString("tenant_name"), rs.getString("currency"),
                        instant(rs, "period_start"), instant(rs, "period_end"),
                        rs.getLong("total_amount_minor"), rs.getLong("paid_amount_minor"),
                        rs.getString("status"), instant(rs, "issued_at")), paged.toArray());
        return new PageResponse<>(spec.page(), spec.size(), total, rows);
    }

    @Transactional(readOnly = true)
    public PageResponse<PaymentRow> payments(Long tenantId, long page, long size,
                                             String status, Long invoiceId) {
        PageSpec spec = page(page, size);
        StringBuilder filter = new StringBuilder();
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (text(status)) {
            filter.append(" AND p.status = ?");
            args.add(status.trim().toUpperCase());
        }
        if (invoiceId != null) {
            filter.append(" AND p.invoice_id = ?");
            args.add(invoiceId);
        }
        long total = count("SELECT COUNT(*) FROM mb_payment p WHERE p.tenant_id = ?" + filter, args);
        List<Object> paged = new ArrayList<>(args);
        paged.add(spec.size());
        paged.add(spec.offset());
        List<PaymentRow> rows = jdbc.query("""
                SELECT p.id, p.invoice_id, p.external_reference,
                       COALESCE(c.channel_code, 'SIMULATED') channel_code,
                       p.currency, p.amount_minor, p.status, p.created_at, p.completed_at
                FROM mb_payment p
                LEFT JOIN mb_channel_transaction c
                  ON c.payment_id = p.id AND c.transaction_type = 'CHARGE'
                WHERE p.tenant_id = ?
                """ + filter + " ORDER BY p.id DESC LIMIT ? OFFSET ?",
                (rs, row) -> paymentRow(rs), paged.toArray());
        return new PageResponse<>(spec.page(), spec.size(), total, rows);
    }

    @Transactional(readOnly = true)
    public PaymentInvestigation paymentInvestigation(Long tenantId, Long paymentId) {
        List<PaymentRow> payments = jdbc.query("""
                SELECT p.id, p.invoice_id, p.external_reference,
                       COALESCE(c.channel_code, 'SIMULATED') channel_code,
                       p.currency, p.amount_minor, p.status, p.created_at, p.completed_at
                FROM mb_payment p
                LEFT JOIN mb_channel_transaction c
                  ON c.payment_id = p.id AND c.transaction_type = 'CHARGE'
                WHERE p.tenant_id = ? AND p.id = ?
                """, (rs, row) -> paymentRow(rs), tenantId, paymentId);
        if (payments.isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "付款记录不存在");
        }
        PaymentRow payment = payments.getFirst();
        List<ChannelFact> channels = jdbc.query("""
                SELECT channel_code, external_reference, transaction_type, currency,
                       amount_minor, status, created_at, updated_at
                FROM mb_channel_transaction
                WHERE tenant_id = ? AND payment_id = ? AND transaction_type = 'CHARGE'
                ORDER BY id DESC LIMIT 1
                """, (rs, row) -> new ChannelFact(
                rs.getString("channel_code"), rs.getString("external_reference"),
                rs.getString("transaction_type"), rs.getString("currency"),
                rs.getLong("amount_minor"), rs.getString("status"),
                instant(rs, "created_at"), instant(rs, "updated_at")), tenantId, paymentId);
        ChannelFact channel = channels.isEmpty() ? null : channels.getFirst();

        List<TimelineEvent> timeline = new ArrayList<>();
        timeline.add(new TimelineEvent("CREATED", "创建支付请求", "付款请求已写入数据库",
                payment.status(), payment.createdAt()));
        if (channel != null) {
            timeline.add(new TimelineEvent("CHANNEL", "渠道交易", "模拟渠道已记录交易事实",
                    channel.status(), channel.updatedAt()));
        }
        jdbc.query("""
                SELECT action, outcome, created_at FROM mb_security_audit
                WHERE tenant_id = ? AND target_type = 'PAYMENT' AND target_id = ?
                ORDER BY id
                """, rs -> {
            timeline.add(new TimelineEvent("AUDIT", auditTitle(rs.getString("action")),
                    rs.getString("action"), rs.getString("outcome"), instant(rs, "created_at")));
        }, tenantId, paymentId.toString());
        timeline.sort((left, right) -> left.occurredAt().compareTo(right.occurredAt()));

        List<RefundRow> refunds = jdbc.query("""
                SELECT id, payment_id, external_reference, currency, amount_minor,
                       status, created_at, completed_at
                FROM mb_refund
                WHERE tenant_id = ? AND payment_id = ?
                ORDER BY id DESC
                """, (rs, row) -> new RefundRow(
                rs.getLong("id"), rs.getLong("payment_id"), rs.getString("external_reference"),
                rs.getString("currency"), rs.getLong("amount_minor"), rs.getString("status"),
                instant(rs, "created_at"), instant(rs, "completed_at")), tenantId, paymentId);
        List<ReconciliationRow> differences = differences(tenantId, null, 100).stream()
                .filter(item -> paymentId.toString().equals(item.referenceId())
                        || payment.externalReference().equals(item.referenceId()))
                .toList();
        List<OperationTask> tasks = tasks(tenantId, 1, 100, null).records().stream()
                .filter(item -> paymentId.toString().equals(item.referenceId())
                        || payment.invoiceId().toString().equals(item.referenceId()))
                .toList();
        return new PaymentInvestigation(payment, channel, timeline, refunds, differences, tasks);
    }

    @Transactional(readOnly = true)
    public PageResponse<SubscriptionRow> subscriptions(Long tenantId, long page, long size,
                                                       String status) {
        PageSpec spec = page(page, size);
        String filter = text(status) ? " AND s.status = ?" : "";
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (text(status)) {
            args.add(status.trim().toUpperCase());
        }
        long total = count("SELECT COUNT(*) FROM mb_subscription s WHERE s.tenant_id = ?" + filter, args);
        args.add(spec.size());
        args.add(spec.offset());
        List<SubscriptionRow> rows = jdbc.query("""
                SELECT s.id, s.plan_id, p.plan_code, p.name plan_name, s.status,
                       sp.currency, sp.base_amount_minor, sp.period_start, sp.period_end
                FROM mb_subscription s
                JOIN mb_plan p ON p.id = s.plan_id
                LEFT JOIN mb_subscription_period sp
                  ON sp.subscription_id = s.id AND sp.status = 'ACTIVE'
                WHERE s.tenant_id = ?
                """ + filter + " ORDER BY s.id DESC LIMIT ? OFFSET ?",
                (rs, row) -> new SubscriptionRow(
                        rs.getLong("id"), rs.getLong("plan_id"), rs.getString("plan_code"),
                        rs.getString("plan_name"), rs.getString("status"),
                        rs.getString("currency"), rs.getObject("base_amount_minor", Long.class),
                        instant(rs, "period_start"), instant(rs, "period_end")), args.toArray());
        return new PageResponse<>(spec.page(), spec.size(), total, rows);
    }

    @Transactional(readOnly = true)
    public List<AvailablePlan> availablePlans() {
        return jdbc.query("""
                SELECT p.id,pr.name product_name,p.plan_code,p.name plan_name
                FROM mb_plan p JOIN mb_product pr ON pr.id=p.product_id
                WHERE p.status='PUBLISHED' AND pr.status='PUBLISHED'
                ORDER BY pr.name,p.name,p.id
                """, (rs, row) -> new AvailablePlan(rs.getLong("id"), rs.getString("product_name"),
                rs.getString("plan_code"), rs.getString("plan_name")));
    }

    @Transactional(readOnly = true)
    public PageResponse<UsageEventRow> usageEvents(Long tenantId, long page, long size,
                                                   String status, String keyword) {
        PageSpec spec = page(page, size);
        StringBuilder where = new StringBuilder(" WHERE e.tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (text(status)) {
            where.append(" AND e.status = ?");
            args.add(status.trim().toUpperCase());
        }
        if (text(keyword)) {
            where.append(" AND (e.event_id LIKE ? OR e.source_id LIKE ? OR e.model_code LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        long total = count("SELECT COUNT(*) FROM mb_usage_event e" + where, args);
        args.add(spec.size());
        args.add(spec.offset());
        List<UsageEventRow> rows = jdbc.query("""
                SELECT e.id, e.subscription_id, e.source_id, e.event_id, m.meter_code,
                       e.model_code, e.quantity, e.status, e.delivery_count, e.last_error,
                       e.occurred_at, e.received_at, e.processed_at
                FROM mb_usage_event e
                JOIN mb_usage_meter m ON m.id = e.meter_id
                """ + where + " ORDER BY e.id DESC LIMIT ? OFFSET ?",
                (rs, row) -> new UsageEventRow(
                        rs.getLong("id"), rs.getLong("subscription_id"), rs.getString("source_id"),
                        rs.getString("event_id"), rs.getString("meter_code"), rs.getString("model_code"),
                        rs.getLong("quantity"), rs.getString("status"), rs.getInt("delivery_count"),
                        rs.getString("last_error"), instant(rs, "occurred_at"),
                        instant(rs, "received_at"), instant(rs, "processed_at")), args.toArray());
        return new PageResponse<>(spec.page(), spec.size(), total, rows);
    }

    @Transactional(readOnly = true)
    public PageResponse<OperationTask> tasks(Long tenantId, long page, long size, String status) {
        PageSpec spec = page(page, size);
        String filter = text(status) ? " AND o.status = ?" : " AND o.status <> 'PUBLISHED'";
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (text(status)) {
            args.add(status.trim().toUpperCase());
        }
        long total = count("""
                SELECT COUNT(*) FROM mb_outbox o
                JOIN mb_usage_event e ON e.id = o.event_id
                WHERE e.tenant_id = ?
                """ + filter, args);
        args.add(spec.size());
        args.add(spec.offset());
        List<OperationTask> rows = jdbc.query("""
                SELECT o.id, o.event_type, o.event_id, o.status, o.attempt_count,
                       o.last_error, o.next_retry_at, o.created_at
                FROM mb_outbox o
                JOIN mb_usage_event e ON e.id = o.event_id
                WHERE e.tenant_id = ?
                """ + filter + " ORDER BY o.id DESC LIMIT ? OFFSET ?",
                (rs, row) -> new OperationTask(
                        rs.getLong("id"), rs.getString("event_type"), "USAGE_EVENT",
                        String.valueOf(rs.getLong("event_id")), rs.getString("status"),
                        rs.getInt("attempt_count"), rs.getString("last_error"),
                        instant(rs, "next_retry_at"), instant(rs, "created_at")), args.toArray());
        return new PageResponse<>(spec.page(), spec.size(), total, rows);
    }

    @Transactional(readOnly = true)
    public List<MemberRow> members(Long tenantId) {
        return jdbc.query("""
                SELECT m.id member_id, u.id user_id, u.email, u.display_name, m.status,
                       GROUP_CONCAT(DISTINCT r.role_code ORDER BY r.role_code SEPARATOR ',') roles,
                       m.created_at
                FROM mb_tenant_member m
                JOIN mb_user u ON u.id = m.user_id
                LEFT JOIN mb_member_role mr ON mr.tenant_id = m.tenant_id AND mr.member_id = m.id
                LEFT JOIN mb_role r ON r.tenant_id = mr.tenant_id AND r.id = mr.role_id
                WHERE m.tenant_id = ?
                GROUP BY m.id, u.id, u.email, u.display_name, m.status, m.created_at
                ORDER BY m.id
                """, (rs, row) -> new MemberRow(
                rs.getLong("member_id"), rs.getLong("user_id"), rs.getString("email"),
                rs.getString("display_name"), rs.getString("status"), split(rs.getString("roles")),
                instant(rs, "created_at")), tenantId);
    }

    @Transactional(readOnly = true)
    public List<LedgerEntryRow> ledgerEntries(Long tenantId, Long invoiceId,
                                               String businessType, int limit) {
        if (limit < 1 || limit > 500) {
            throw invalid("limit 必须在 1 到 500 之间");
        }
        StringBuilder filter = new StringBuilder(" WHERE t.tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (text(businessType)) {
            filter.append(" AND t.business_type = ?");
            args.add(businessType);
        }
        if (invoiceId != null) {
            filter.append("""
                     AND (
                       (t.business_type = 'INVOICE' AND t.business_id = ?)
                       OR (t.business_type = 'INVOICE_ADJUSTMENT' AND EXISTS
                           (SELECT 1 FROM mb_invoice_adjustment a WHERE a.id=t.business_id AND a.invoice_id=?))
                       OR (t.business_type IN ('PAYMENT','PAYMENT_REVERSAL') AND EXISTS
                           (SELECT 1 FROM mb_payment p WHERE p.id=t.business_id AND p.invoice_id=?))
                       OR (t.business_type = 'REFUND' AND EXISTS
                           (SELECT 1 FROM mb_refund r JOIN mb_payment p ON p.id=r.payment_id
                            WHERE r.id=t.business_id AND p.invoice_id=?))
                     )
                    """);
            args.add(invoiceId);
            args.add(invoiceId);
            args.add(invoiceId);
            args.add(invoiceId);
        }
        args.add(limit);
        return jdbc.query("""
                SELECT t.id transaction_id,t.business_type,t.business_id,t.posting_action,
                       t.currency,t.description,e.id entry_id,a.account_code,a.account_type,
                       e.direction,e.amount_minor,e.created_at
                FROM mb_ledger_transaction t
                JOIN mb_ledger_entry e ON e.transaction_id=t.id
                JOIN mb_ledger_account a ON a.id=e.account_id
                """ + filter + " ORDER BY t.id DESC,e.id LIMIT ?", (rs, row) -> new LedgerEntryRow(
                rs.getLong("transaction_id"), rs.getString("business_type"), rs.getLong("business_id"),
                rs.getString("posting_action"), rs.getString("currency"), rs.getString("description"),
                rs.getLong("entry_id"), rs.getString("account_code"), rs.getString("account_type"),
                rs.getString("direction"), rs.getLong("amount_minor"), instant(rs, "created_at")), args.toArray());
    }

    @Transactional(readOnly = true)
    public List<ReconciliationRow> differences(Long tenantId, String status, int limit) {
        if (limit < 1 || limit > 200) {
            throw invalid("limit 必须在 1 到 200 之间");
        }
        String filter = text(status) ? " AND status = ?" : "";
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (text(status)) {
            args.add(status.trim().toUpperCase());
        }
        args.add(limit);
        return jdbc.query("""
                SELECT id, difference_type, reference_type, reference_id, currency,
                       expected_amount_minor, actual_amount_minor, expected_status,
                       actual_status, status, evidence, first_seen_at, last_seen_at
                FROM mb_reconciliation_difference
                WHERE tenant_id = ?
                """ + filter + " ORDER BY id DESC LIMIT ?",
                (rs, row) -> new ReconciliationRow(
                        rs.getLong("id"), rs.getString("difference_type"),
                        rs.getString("reference_type"), rs.getString("reference_id"),
                        rs.getString("currency"), rs.getObject("expected_amount_minor", Long.class),
                        rs.getObject("actual_amount_minor", Long.class), rs.getString("expected_status"),
                        rs.getString("actual_status"), rs.getString("status"), rs.getString("evidence"),
                        instant(rs, "first_seen_at"), instant(rs, "last_seen_at")), args.toArray());
    }

    private PaymentRow paymentRow(ResultSet rs) throws SQLException {
        return new PaymentRow(
                rs.getLong("id"), rs.getLong("invoice_id"), rs.getString("external_reference"),
                rs.getString("channel_code"), rs.getString("currency"), rs.getLong("amount_minor"),
                rs.getString("status"), instant(rs, "created_at"), instant(rs, "completed_at"));
    }

    private long scalar(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private long count(String sql, List<Object> args) {
        Long value = jdbc.queryForObject(sql, Long.class, args.toArray());
        return value == null ? 0 : value;
    }

    private PageSpec page(long page, long size) {
        if (page < 1 || size < 1 || size > 200) {
            throw invalid("分页参数不合法");
        }
        return new PageSpec(page, size, Math.multiplyExact(page - 1, size));
    }

    private boolean text(String value) {
        return value != null && !value.isBlank();
    }

    private List<String> split(String value) {
        return text(value) ? Arrays.asList(value.split(",")) : List.of();
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private String auditTitle(String action) {
        if (action == null) {
            return "操作记录";
        }
        if (action.contains("CHANNEL_QUERY")) {
            return "查询渠道结果";
        }
        if (action.contains("RESULT")) {
            return "更新支付结果";
        }
        if (action.contains("REVERSE")) {
            return "支付冲正";
        }
        return "操作记录";
    }

    private BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.INVALID_ARGUMENT, message);
    }

    private record PageSpec(long page, long size, long offset) {
    }

    private record MoneyTotals(String currency, long receivable, long pendingInvoices) {
    }
}
