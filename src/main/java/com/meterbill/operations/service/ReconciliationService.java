package com.meterbill.operations.service;

import com.meterbill.common.exception.BusinessException;
import com.meterbill.common.exception.ErrorCode;
import com.meterbill.identity.service.SecurityAuditService;
import com.meterbill.operations.web.vo.ReconciliationDifferenceVO;
import com.meterbill.operations.web.vo.ReconciliationRunVO;
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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ReconciliationService {

    private final JdbcTemplate jdbc;
    private final SecurityAuditService audit;

    @Transactional
    public ReconciliationRunVO run(Long tenantId, String idempotencyKey, Long actorId) {
        validateIdempotencyKey(idempotencyKey);
        RunRow existing = findRun(tenantId, idempotencyKey, false);
        if (existing != null) {
            return getRun(tenantId, existing.id());
        }

        String requestHash = hash("reconcile|" + tenantId);
        Long runId;
        try {
            jdbc.update("""
                    INSERT INTO mb_reconciliation_run
                        (tenant_id, idempotency_key, request_hash, status)
                    VALUES (?, ?, ?, 'RUNNING')
                    """, tenantId, idempotencyKey, requestHash);
            runId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        } catch (DuplicateKeyException ex) {
            RunRow raced = findRun(tenantId, idempotencyKey, true);
            if (raced == null) {
                throw ex;
            }
            return getRun(tenantId, raced.id());
        }

        reconcileInvoices(tenantId, runId);
        reconcileTransactions(tenantId, runId);
        reconcileChannelCash(tenantId, runId);
        jdbc.update("""
                UPDATE mb_reconciliation_run
                SET status = 'COMPLETED', completed_at = UTC_TIMESTAMP(6)
                WHERE id = ? AND tenant_id = ?
                """, runId, tenantId);
        audit.record(tenantId, actorId, "RECONCILIATION_RUN", "RECONCILIATION_RUN",
                runId.toString());
        return getRun(tenantId, runId);
    }

    @Transactional(readOnly = true)
    public ReconciliationRunVO getRun(Long tenantId, Long runId) {
        List<RunRow> rows = jdbc.query("""
                SELECT id, idempotency_key, status, started_at, completed_at
                FROM mb_reconciliation_run WHERE tenant_id = ? AND id = ?
                """, (rs, row) -> new RunRow(rs.getLong("id"), rs.getString("idempotency_key"),
                rs.getString("status"), rs.getObject("started_at", LocalDateTime.class),
                rs.getObject("completed_at", LocalDateTime.class)), tenantId, runId);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "对账批次不存在");
        }
        RunRow run = rows.getFirst();
        List<ReconciliationDifferenceVO> differences = jdbc.query("""
                SELECT d.id, d.difference_type, d.reference_type, d.reference_id,
                       d.tenant_id,
                       d.currency, d.expected_amount_minor, d.actual_amount_minor,
                       d.expected_status, d.actual_status, d.status, d.evidence,
                       d.resolution_note, d.first_seen_at, d.last_seen_at, d.resolved_at
                FROM mb_reconciliation_run_difference rd
                JOIN mb_reconciliation_difference d ON d.id = rd.difference_id
                WHERE rd.run_id = ? AND d.tenant_id = ?
                ORDER BY d.id
                """, (rs, row) -> differenceVO(rs), runId, tenantId);
        return new ReconciliationRunVO(run.id(), run.idempotencyKey(), run.status(),
                instant(run.startedAt()), instant(run.completedAt()), differences);
    }

    @Transactional(readOnly = true)
    public List<ReconciliationDifferenceVO> listDifferences(String status, int limit) {
        if (status == null || !List.of("OPEN", "INVESTIGATING", "RESOLVED", "WAIVED")
                .contains(status)) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "差异状态不合法");
        }
        if (limit < 1 || limit > 500) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "limit 必须在 1 到 500 之间");
        }
        return jdbc.query("""
                SELECT id, tenant_id, difference_type, reference_type, reference_id, currency,
                       expected_amount_minor, actual_amount_minor, expected_status,
                       actual_status, status, evidence, resolution_note,
                       first_seen_at, last_seen_at, resolved_at
                FROM mb_reconciliation_difference
                WHERE status = ?
                ORDER BY last_seen_at DESC, id DESC
                LIMIT ?
                """, (rs, row) -> differenceVO(rs), status, limit);
    }

    @Transactional
    public ReconciliationDifferenceVO updateDifferenceStatus(
            Long differenceId, String newStatus, String note, Long actorId) {
        if (newStatus == null || !List.of("OPEN", "INVESTIGATING", "RESOLVED", "WAIVED")
                .contains(newStatus)) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "差异状态不合法");
        }
        if (note == null || note.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "差异处理说明不能为空");
        }
        List<DifferenceStatusRow> rows = jdbc.query("""
                SELECT tenant_id, status FROM mb_reconciliation_difference
                WHERE id = ? FOR UPDATE
                """, (rs, row) -> new DifferenceStatusRow(
                rs.getLong("tenant_id"), rs.getString("status")), differenceId);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "对账差异不存在");
        }
        DifferenceStatusRow row = rows.getFirst();
        requireLegalTransition(row.status(), newStatus);
        jdbc.update("""
                UPDATE mb_reconciliation_difference
                SET status = ?, resolution_note = ?, resolved_by = ?,
                    resolved_at = CASE WHEN ? IN ('RESOLVED', 'WAIVED')
                                       THEN UTC_TIMESTAMP(6) ELSE NULL END
                WHERE id = ?
                """, newStatus, note.trim(), actorId, newStatus, differenceId);
        jdbc.update("""
                INSERT INTO mb_reconciliation_difference_action
                    (difference_id, actor_id, previous_status, new_status, note)
                VALUES (?, ?, ?, ?, ?)
                """, differenceId, actorId, row.status(), newStatus, note.trim());
        audit.record(row.tenantId(), actorId, "RECONCILIATION_DIFFERENCE_" + newStatus,
                "RECONCILIATION_DIFFERENCE", differenceId.toString());
        return getDifference(differenceId);
    }

    private void reconcileInvoices(Long tenantId, Long runId) {
        List<InvoiceCheck> invoices = jdbc.query("""
                SELECT i.id, i.currency, i.total_amount_minor, i.status,
                       COALESCE((SELECT SUM(a.amount_minor)
                                 FROM mb_invoice_adjustment a
                                 WHERE a.invoice_id = i.id AND a.status <> 'VOIDED'), 0)
                           AS adjustment_total_minor,
                       COALESCE((SELECT SUM(CASE e.direction WHEN 'DEBIT' THEN e.amount_minor
                                                              ELSE -e.amount_minor END)
                                 FROM mb_ledger_transaction lt
                                 JOIN mb_ledger_entry e ON e.transaction_id = lt.id
                                 JOIN mb_ledger_account la ON la.id = e.account_id
                                 WHERE lt.tenant_id = i.tenant_id AND la.account_code = 'AR'
                                   AND ((lt.business_type = 'INVOICE' AND lt.business_id = i.id)
                                     OR (lt.business_type = 'INVOICE_ADJUSTMENT'
                                         AND lt.business_id IN (
                                             SELECT a.id FROM mb_invoice_adjustment a
                                             WHERE a.invoice_id = i.id AND a.status <> 'VOIDED')))), 0)
                           AS ledger_ar_minor,
                       COALESCE((SELECT SUM(p.amount_minor) FROM mb_payment p
                                 WHERE p.tenant_id = i.tenant_id AND p.invoice_id = i.id
                                   AND p.status = 'SUCCEEDED'), 0) AS paid_minor
                FROM mb_invoice i
                WHERE i.tenant_id = ? AND i.status <> 'VOIDED'
                ORDER BY i.id
                """, (rs, row) -> new InvoiceCheck(rs.getLong("id"), rs.getString("currency"),
                rs.getLong("total_amount_minor"), rs.getLong("adjustment_total_minor"),
                rs.getLong("ledger_ar_minor"), rs.getLong("paid_minor"), rs.getString("status")), tenantId);
        for (InvoiceCheck invoice : invoices) {
            long obligation = addExact(invoice.totalMinor(), invoice.adjustmentMinor());
            if (obligation != invoice.ledgerArMinor()) {
                recordDifference(tenantId, runId,
                        "INVOICE_LEDGER_MISMATCH:" + invoice.id(), "INVOICE_LEDGER_MISMATCH",
                        "INVOICE", invoice.id().toString(), invoice.currency(), obligation,
                        invoice.ledgerArMinor(), null, null,
                        "Invoice obligation is compared with posted AR for the invoice and its active adjustments.");
            }
            String expectedStatus = invoiceStatus(obligation, invoice.paidMinor());
            if (!expectedStatus.equals(invoice.status())) {
                recordDifference(tenantId, runId,
                        "INVOICE_PAYMENT_STATUS_MISMATCH:" + invoice.id(),
                        "INVOICE_PAYMENT_STATUS_MISMATCH", "INVOICE", invoice.id().toString(),
                        invoice.currency(), obligation, invoice.paidMinor(), expectedStatus,
                        invoice.status(), "Expected status is derived from successful payments and invoice obligation.");
            }
        }
    }

    private void reconcileChannelCash(Long tenantId, Long runId) {
        Map<String, Long> channelTotals = new LinkedHashMap<>();
        jdbc.query("""
                SELECT currency, SUM(CASE transaction_type WHEN 'CHARGE' THEN amount_minor
                                                            ELSE -amount_minor END) AS net_minor
                FROM mb_channel_transaction
                WHERE tenant_id = ? AND status = 'SUCCEEDED'
                GROUP BY currency ORDER BY currency
                """, rs -> {
                    channelTotals.put(rs.getString("currency"), rs.getLong("net_minor"));
                }, tenantId);
        Map<String, Long> ledgerTotals = new LinkedHashMap<>();
        jdbc.query("""
                SELECT lt.currency,
                       SUM(CASE e.direction WHEN 'DEBIT' THEN e.amount_minor
                                            ELSE -e.amount_minor END) AS net_minor
                FROM mb_ledger_transaction lt
                JOIN mb_ledger_entry e ON e.transaction_id = lt.id
                JOIN mb_ledger_account la ON la.id = e.account_id AND la.account_code = 'CASH'
                WHERE lt.tenant_id = ? AND lt.business_type IN ('PAYMENT', 'REFUND', 'PAYMENT_REVERSAL')
                GROUP BY lt.currency ORDER BY lt.currency
                """, rs -> {
                    ledgerTotals.put(rs.getString("currency"), rs.getLong("net_minor"));
                }, tenantId);

        List<String> currencies = new ArrayList<>(channelTotals.keySet());
        for (String currency : ledgerTotals.keySet()) {
            if (!currencies.contains(currency)) {
                currencies.add(currency);
            }
        }
        for (String currency : currencies) {
            long channel = channelTotals.getOrDefault(currency, 0L);
            long ledger = ledgerTotals.getOrDefault(currency, 0L);
            if (channel != ledger) {
                recordDifference(tenantId, runId, "CHANNEL_CASH_MISMATCH:" + currency,
                        "CHANNEL_CASH_MISMATCH", "CURRENCY", currency, currency, channel,
                        ledger, null, null,
                        "Succeeded simulated charges minus refunds are compared with cash ledger postings.");
            }
        }
    }

    private void reconcileTransactions(Long tenant, Long run) {
        var orders = jdbc.query("""
                SELECT id,external_reference,'CHARGE' kind,currency,amount_minor,status FROM mb_payment WHERE tenant_id=?
                UNION ALL
                SELECT id,external_reference,'REFUND' kind,currency,amount_minor,status FROM mb_refund WHERE tenant_id=?
                """, (rs,n)->new TransactionCheck(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getLong(5),rs.getString(6)),tenant,tenant);
        var channels = jdbc.query("SELECT id,external_reference,transaction_type,currency,amount_minor,status FROM mb_channel_transaction WHERE tenant_id=?",
                (rs,n)->new TransactionCheck(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getLong(5),rs.getString(6)),tenant);
        var byReference = new java.util.HashMap<String,TransactionCheck>();
        for (var channel : channels) byReference.put(channel.reference(),channel);
        for (var order : orders) {
            var channel=byReference.remove(order.reference());
            if (channel==null) transactionDifference(tenant,run,"CHANNEL_MISSING",order,null,"Internal order has no channel observation");
            else if (!order.kind().equals(channel.kind()) || !order.currency().equals(channel.currency())
                    || order.amount()!=channel.amount() || !order.status().equals(channel.status()))
                transactionDifference(tenant,run,"CHANNEL_TRANSACTION_MISMATCH",order,channel,
                        "Per-reference type/currency/amount/status comparison; internal="+order.kind()+"/"+order.currency()+", channel="+channel.kind()+"/"+channel.currency());
            boolean charge="CHARGE".equals(order.kind());
            long expected="SUCCEEDED".equals(order.status()) ? (charge ? order.amount() : -order.amount()) : 0;
            Long cash=jdbc.queryForObject("""
                    SELECT COALESCE(SUM(CASE e.direction WHEN 'DEBIT' THEN e.amount_minor ELSE -e.amount_minor END),0)
                    FROM mb_ledger_transaction t JOIN mb_ledger_entry e ON e.transaction_id=t.id
                    JOIN mb_ledger_account a ON a.id=e.account_id AND a.account_code='CASH'
                    WHERE t.tenant_id=? AND t.business_id=?
                    AND ((?='CHARGE' AND t.business_type IN ('PAYMENT','PAYMENT_REVERSAL')) OR (?='REFUND' AND t.business_type='REFUND'))
                    """,Long.class,tenant,order.id(),order.kind(),order.kind());
            if (expected!=cash) recordDifference(tenant,run,"TRANSACTION_LEDGER_MISMATCH:"+order.kind()+":"+order.id(),
                    "TRANSACTION_LEDGER_MISMATCH",order.kind(),order.id().toString(),order.currency(),expected,cash,
                    order.status(),null,"Per-order cash posting including reversal");
        }
        for (var channel:byReference.values()) transactionDifference(tenant,run,"INTERNAL_ORDER_MISSING",channel,null,"Channel observation has no internal order");
    }

    private void transactionDifference(Long tenant,Long run,String type,TransactionCheck expected,TransactionCheck actual,String evidence) {
        recordDifference(tenant,run,type+":"+expected.reference(),type,"CHANNEL_REFERENCE",expected.reference(),expected.currency(),
                expected.amount(),actual==null ? null : actual.amount(),expected.status(),actual==null ? null : actual.status(),evidence);
    }
    private record TransactionCheck(Long id,String reference,String kind,String currency,long amount,String status) { }
    private void recordDifference(Long tenantId, Long runId, String fingerprint,
                                  String type, String referenceType, String referenceId,
                                  String currency, Long expectedAmount, Long actualAmount,
                                  String expectedStatus, String actualStatus, String evidence) {
        jdbc.update("""
                INSERT INTO mb_reconciliation_difference
                    (tenant_id, fingerprint, difference_type, reference_type, reference_id,
                     currency, expected_amount_minor, actual_amount_minor, expected_status,
                     actual_status, status, evidence)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?)
                ON DUPLICATE KEY UPDATE
                    difference_type = VALUES(difference_type),
                    reference_type = VALUES(reference_type),
                    reference_id = VALUES(reference_id), currency = VALUES(currency),
                    expected_amount_minor = VALUES(expected_amount_minor),
                    actual_amount_minor = VALUES(actual_amount_minor),
                    expected_status = VALUES(expected_status), actual_status = VALUES(actual_status),
                    evidence = VALUES(evidence),
                    resolution_note = IF(status IN ('RESOLVED', 'WAIVED'), NULL, resolution_note),
                    resolved_by = IF(status IN ('RESOLVED', 'WAIVED'), NULL, resolved_by),
                    resolved_at = IF(status IN ('RESOLVED', 'WAIVED'), NULL, resolved_at),
                    status = IF(status IN ('RESOLVED', 'WAIVED'), 'OPEN', status),
                    last_seen_at = UTC_TIMESTAMP(6)
                """, tenantId, fingerprint, type, referenceType, referenceId, currency,
                expectedAmount, actualAmount, expectedStatus, actualStatus, evidence);
        Long differenceId = jdbc.queryForObject("""
                SELECT id FROM mb_reconciliation_difference
                WHERE tenant_id = ? AND fingerprint = ?
                """, Long.class, tenantId, fingerprint);
        jdbc.update("""
                INSERT IGNORE INTO mb_reconciliation_run_difference (run_id, difference_id)
                VALUES (?, ?)
                """, runId, differenceId);
    }

    private RunRow findRun(Long tenantId, String key, boolean lock) {
        String sql = """
                SELECT id, idempotency_key, status, started_at, completed_at
                FROM mb_reconciliation_run WHERE tenant_id = ? AND idempotency_key = ?
                """ + (lock ? " FOR UPDATE" : "");
        List<RunRow> rows = jdbc.query(sql, (rs, row) -> new RunRow(
                rs.getLong("id"), rs.getString("idempotency_key"), rs.getString("status"),
                rs.getObject("started_at", LocalDateTime.class),
                rs.getObject("completed_at", LocalDateTime.class)), tenantId, key);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private ReconciliationDifferenceVO getDifference(Long differenceId) {
        List<ReconciliationDifferenceVO> rows = jdbc.query("""
                SELECT id, tenant_id, difference_type, reference_type, reference_id, currency,
                       expected_amount_minor, actual_amount_minor, expected_status,
                       actual_status, status, evidence, resolution_note,
                       first_seen_at, last_seen_at, resolved_at
                FROM mb_reconciliation_difference WHERE id = ?
                """, (rs, row) -> differenceVO(rs), differenceId);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "对账差异不存在");
        }
        return rows.getFirst();
    }

    private ReconciliationDifferenceVO differenceVO(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        return new ReconciliationDifferenceVO(rs.getLong("tenant_id"), rs.getLong("id"),
                rs.getString("difference_type"),
                rs.getString("reference_type"), rs.getString("reference_id"),
                rs.getString("currency"), rs.getObject("expected_amount_minor", Long.class),
                rs.getObject("actual_amount_minor", Long.class), rs.getString("expected_status"),
                rs.getString("actual_status"), rs.getString("status"), rs.getString("evidence"),
                rs.getString("resolution_note"), instant(rs.getObject("first_seen_at", LocalDateTime.class)),
                instant(rs.getObject("last_seen_at", LocalDateTime.class)),
                instant(rs.getObject("resolved_at", LocalDateTime.class)));
    }

    private void requireLegalTransition(String previous, String next) {
        boolean legal = switch (previous) {
            case "OPEN" -> List.of("INVESTIGATING", "RESOLVED", "WAIVED").contains(next);
            case "INVESTIGATING" -> List.of("OPEN", "RESOLVED", "WAIVED").contains(next);
            case "RESOLVED", "WAIVED" -> "OPEN".equals(next);
            default -> false;
        };
        if (!legal) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT,
                    "不允许的差异状态转换: " + previous + " -> " + next);
        }
    }

    private String invoiceStatus(long obligation, long paid) {
        if (obligation <= 0 || paid >= obligation) {
            return "PAID";
        }
        return paid > 0 ? "PARTIALLY_PAID" : "ISSUED";
    }

    private long addExact(long first, long second) {
        try {
            return Math.addExact(first, second);
        } catch (ArithmeticException ex) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT,
                    "对账账单金额超出 BIGINT 范围");
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
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private record RunRow(Long id, String idempotencyKey, String status,
                          LocalDateTime startedAt, LocalDateTime completedAt) {
    }

    private record InvoiceCheck(Long id, String currency, Long totalMinor, Long adjustmentMinor,
                                Long ledgerArMinor, Long paidMinor, String status) {
    }

    private record DifferenceStatusRow(Long tenantId, String status) {
    }
}
