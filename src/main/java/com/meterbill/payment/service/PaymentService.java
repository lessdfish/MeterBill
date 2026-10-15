package com.meterbill.payment.service;

import com.meterbill.common.exception.BusinessException;
import com.meterbill.common.exception.ErrorCode;
import com.meterbill.identity.service.SecurityAuditService;
import com.meterbill.ledger.service.LedgerService;
import com.meterbill.billing.service.InvoiceAccountingService;
import com.meterbill.payment.web.vo.PaymentVO;
import com.meterbill.payment.web.vo.RefundVO;
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
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final JdbcTemplate jdbc;
    private final LedgerService ledger;
    private final InvoiceAccountingService invoiceAccounting;
    private final SecurityAuditService audit;

    @Transactional
    public Long reversePayment(Long paymentId, Long actorId, String reason) {
        if (actorId == null || reason == null || reason.isBlank() || reason.length() > 500)
            throw conflict("冲正必须记录操作人和原因（最多500字）");
        PaymentRow lookup = requirePayment(paymentId, false);
        requireInvoice(lookup.tenantId(), lookup.invoiceId(), true);
        PaymentRow payment = requirePayment(paymentId, true);
        var existing = jdbc.queryForList("SELECT id FROM mb_payment_reversal WHERE payment_id=? FOR UPDATE", Long.class, paymentId);
        if (!existing.isEmpty()) {
            String previous = jdbc.queryForObject("SELECT reason FROM mb_payment_reversal WHERE payment_id=? FOR UPDATE", String.class, paymentId);
            if (!reason.trim().equals(previous)) throw conflict("该付款已以不同原因冲正");
            return existing.getFirst();
        }
        if (!"SUCCEEDED".equals(payment.status())) throw conflict("仅成功付款可冲正");
        if (sumRefunds(paymentId) != 0) throw conflict("存在待处理、未知或成功退款，不能冲正");
        if (!"SUCCEEDED".equals(channelResult(payment.tenantId(), payment.externalReference(), "CHARGE", payment.currency(), payment.amountMinor())))
            throw conflict("渠道状态不一致，先核查渠道流水");
        Long original = jdbc.queryForObject("SELECT id FROM mb_ledger_transaction WHERE tenant_id=? AND business_type='PAYMENT' AND business_id=? AND posting_action='SETTLE' FOR UPDATE",
                Long.class, payment.tenantId(), paymentId);
        Long reversal = ledger.post(payment.tenantId(), "PAYMENT_REVERSAL", paymentId, "REVERSE", payment.currency(), reason.trim(), List.of(
                entry("AR", "ASSET", "DEBIT", payment.amountMinor()), entry("CASH", "ASSET", "CREDIT", payment.amountMinor())));
        jdbc.update("INSERT INTO mb_payment_reversal(tenant_id,payment_id,original_transaction_id,reversal_transaction_id,reason,actor_id) VALUES (?,?,?,?,?,?)",
                payment.tenantId(), paymentId, original, reversal, reason.trim(), actorId);
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("UPDATE mb_payment SET status='REVERSED' WHERE id=?", paymentId);
        jdbc.update("UPDATE mb_channel_transaction SET status='REVERSED' WHERE tenant_id=? AND external_reference=?", payment.tenantId(), payment.externalReference());
        invoiceAccounting.synchronize(payment.tenantId(), payment.invoiceId());
        audit.record(payment.tenantId(), actorId, "PAYMENT_REVERSE", "PAYMENT", paymentId.toString());
        return id;
    }

    @Transactional
    public PaymentVO queryPaymentChannel(Long id, Long actor) {
        PaymentRow lookup = requirePayment(id, false);
        requireInvoice(lookup.tenantId(), lookup.invoiceId(), true);
        PaymentRow payment = requirePayment(id, true);
        String result = channelResult(payment.tenantId(), payment.externalReference(), "CHARGE", payment.currency(), payment.amountMinor());
        audit.record(payment.tenantId(), actor, "PAYMENT_CHANNEL_QUERY", "PAYMENT", id.toString());
        if (result == null || "PENDING".equals(result) || result.equals(payment.status())) return getPayment(payment.tenantId(), id);
        return simulatePaymentResult(id, actor, result);
    }

    @Transactional
    public RefundVO queryRefundChannel(Long id, Long actor) {
        RefundRow refund = requireRefund(id, true);
        String result = channelResult(refund.tenantId(), refund.externalReference(), "REFUND", refund.currency(), refund.amountMinor());
        audit.record(refund.tenantId(), actor, "REFUND_CHANNEL_QUERY", "REFUND", id.toString());
        if (result == null || "PENDING".equals(result) || result.equals(refund.status())) return getRefund(refund.tenantId(), id);
        return simulateRefundResult(id, actor, result);
    }

    private String channelResult(Long tenant, String reference, String type, String currency, long amount) {
        var rows = jdbc.query("SELECT transaction_type,currency,amount_minor,status FROM mb_channel_transaction WHERE tenant_id=? AND external_reference=? FOR UPDATE",
                (rs,n) -> new Object[]{rs.getString(1),rs.getString(2),rs.getLong(3),rs.getString(4)}, tenant, reference);
        if (rows.isEmpty()) return null; // A missing/unknown observation is not evidence of failure.
        Object[] row = rows.getFirst();
        if (!type.equals(row[0]) || !currency.equals(row[1]) || amount != (Long)row[2]) throw conflict("渠道金额、币种或交易类型不一致");
        return (String)row[3];
    }

    @Transactional
    public PaymentVO createPayment(Long tenantId, Long invoiceId, String idempotencyKey,
                                   Long amountMinor, Long actorId) {
        validateIdempotencyKey(idempotencyKey);
        String requestHash = hash(tenantId + "|" + invoiceId + "|" + amountMinor);
        PaymentRow existing = findPaymentByIdempotency(tenantId, idempotencyKey);
        if (existing != null) {
            requireSameRequest(existing.requestHash(), requestHash);
            return getPayment(tenantId, existing.id());
        }

        InvoiceHeader invoice = requireInvoice(tenantId, invoiceId, true);
        if ("VOIDED".equals(invoice.status())) {
            throw conflict("已作废账单不能付款");
        }
        existing = findPaymentByIdempotencyCurrent(tenantId, idempotencyKey);
        if (existing != null) {
            requireSameRequest(existing.requestHash(), requestHash);
            return getPayment(tenantId, existing.id());
        }

        invoiceAccounting.synchronize(tenantId, invoiceId);
        long obligation = invoiceObligation(invoice);
        if (obligation <= 0) {
            throw conflict("账单没有待付款余额");
        }
        long reserved = sumPayments(tenantId, invoiceId,
                "status IN ('PENDING', 'UNKNOWN', 'SUCCEEDED')");
        long remaining = subtractExact(obligation, reserved, "账单余额计算溢出");
        if (amountMinor == null || amountMinor <= 0 || amountMinor > remaining) {
            throw conflict("付款金额必须大于零且不能超过账单未付余额");
        }

        String externalReference = "sim-pay-" + UUID.randomUUID();
        Long paymentId;
        try {
            jdbc.update("""
                    INSERT INTO mb_payment
                        (tenant_id, invoice_id, idempotency_key, request_hash,
                         external_reference, currency, amount_minor, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING')
                    """, tenantId, invoiceId, idempotencyKey, requestHash,
                    externalReference, invoice.currency(), amountMinor);
            paymentId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        } catch (DuplicateKeyException ex) {
            PaymentRow raced = findPaymentByIdempotencyCurrent(tenantId, idempotencyKey);
            if (raced == null) {
                throw ex;
            }
            requireSameRequest(raced.requestHash(), requestHash);
            return getPayment(tenantId, raced.id());
        }
        jdbc.update("""
                INSERT INTO mb_channel_transaction
                    (tenant_id, payment_id, channel_code, external_reference,
                     transaction_type, currency, amount_minor, status)
                VALUES (?, ?, 'SIMULATED', ?, 'CHARGE', ?, ?, 'PENDING')
                """, tenantId, paymentId, externalReference, invoice.currency(), amountMinor);
        audit.record(tenantId, actorId, "PAYMENT_CREATE", "PAYMENT", paymentId.toString());
        return getPayment(tenantId, paymentId);
    }

    @Transactional
    public PaymentVO simulatePaymentResult(Long paymentId, Long actorId, String result) {
        validateResult(result);
        PaymentRow lookup = requirePayment(paymentId, false);
        requireInvoice(lookup.tenantId(), lookup.invoiceId(), true);
        PaymentRow payment = requirePayment(paymentId, true);
        if (result.equals(payment.status())) {
            return getPayment(payment.tenantId(), payment.id());
        }
        if (!("PENDING".equals(payment.status()) || "UNKNOWN".equals(payment.status()))) {
            throw conflict("付款已进入终态，不能改写结果");
        }
        requireCompatibleChannel(payment.tenantId(), payment.externalReference(), "CHARGE", payment.currency(), payment.amountMinor(), result);

        boolean completed = "SUCCEEDED".equals(result) || "FAILED".equals(result);
        jdbc.update("""
                UPDATE mb_payment SET status = ?,
                    completed_at = CASE WHEN ? THEN UTC_TIMESTAMP(6) ELSE NULL END
                WHERE id = ? AND status IN ('PENDING', 'UNKNOWN')
                """, result, completed, paymentId);
        jdbc.update("""
                UPDATE mb_channel_transaction SET status = ?, updated_at = UTC_TIMESTAMP(6)
                WHERE payment_id = ? AND transaction_type = 'CHARGE'
                """, result, paymentId);

        if ("SUCCEEDED".equals(result)) {
            ledger.post(payment.tenantId(), "PAYMENT", payment.id(), "SETTLE",
                    payment.currency(), "Simulated payment settlement", List.of(
                            entry("CASH", "ASSET", "DEBIT", payment.amountMinor()),
                            entry("AR", "ASSET", "CREDIT", payment.amountMinor())));
            invoiceAccounting.synchronize(payment.tenantId(), payment.invoiceId());
        }
        audit.record(payment.tenantId(), actorId, "PAYMENT_RESULT_" + result,
                "PAYMENT", payment.id().toString());
        return getPayment(payment.tenantId(), payment.id());
    }

    @Transactional(readOnly = true)
    public PaymentVO getPayment(Long tenantId, Long paymentId) {
        List<PaymentVO> rows = jdbc.query("""
                SELECT id, tenant_id, invoice_id, external_reference, currency,
                       amount_minor, status, created_at, completed_at
                FROM mb_payment WHERE tenant_id = ? AND id = ?
                """, (rs, row) -> new PaymentVO(
                rs.getLong("id"), rs.getLong("tenant_id"), rs.getLong("invoice_id"),
                rs.getString("external_reference"), rs.getString("currency"),
                rs.getLong("amount_minor"), rs.getString("status"),
                instant(rs.getObject("created_at", LocalDateTime.class)),
                instant(rs.getObject("completed_at", LocalDateTime.class))), tenantId, paymentId);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "付款记录不存在");
        }
        return rows.getFirst();
    }

    @Transactional
    public RefundVO requestRefund(Long tenantId, Long paymentId, String idempotencyKey,
                                  Long amountMinor, Long actorId) {
        validateIdempotencyKey(idempotencyKey);
        String requestHash = hash(tenantId + "|" + paymentId + "|" + amountMinor);
        RefundRow existing = findRefundByIdempotency(tenantId, idempotencyKey);
        if (existing != null) {
            requireSameRequest(existing.requestHash(), requestHash);
            return getRefund(tenantId, existing.id());
        }

        PaymentRow payment = requirePayment(tenantId, paymentId, true);
        existing = findRefundByIdempotencyCurrent(tenantId, idempotencyKey);
        if (existing != null) {
            requireSameRequest(existing.requestHash(), requestHash);
            return getRefund(tenantId, existing.id());
        }
        if (!"SUCCEEDED".equals(payment.status())) {
            throw conflict("只有已成功付款才能申请退款");
        }
        long reservedRefunds = sumRefunds(paymentId);
        long refundable = subtractExact(payment.amountMinor(), reservedRefunds,
                "退款余额计算溢出");
        if (amountMinor == null || amountMinor <= 0 || amountMinor > refundable) {
            throw conflict("退款金额必须大于零且不能超过该付款的剩余可退金额");
        }

        String externalReference = "sim-refund-" + UUID.randomUUID();
        Long refundId;
        try {
            jdbc.update("""
                    INSERT INTO mb_refund
                        (tenant_id, payment_id, idempotency_key, request_hash,
                         external_reference, currency, amount_minor, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING')
                    """, tenantId, paymentId, idempotencyKey, requestHash,
                    externalReference, payment.currency(), amountMinor);
            refundId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        } catch (DuplicateKeyException ex) {
            RefundRow raced = findRefundByIdempotencyCurrent(tenantId, idempotencyKey);
            if (raced == null) {
                throw ex;
            }
            requireSameRequest(raced.requestHash(), requestHash);
            return getRefund(tenantId, raced.id());
        }
        jdbc.update("""
                INSERT INTO mb_channel_transaction
                    (tenant_id, refund_id, channel_code, external_reference,
                     transaction_type, currency, amount_minor, status)
                VALUES (?, ?, 'SIMULATED', ?, 'REFUND', ?, ?, 'PENDING')
                """, tenantId, refundId, externalReference, payment.currency(), amountMinor);
        audit.record(tenantId, actorId, "REFUND_CREATE", "REFUND", refundId.toString());
        return getRefund(tenantId, refundId);
    }

    @Transactional
    public RefundVO simulateRefundResult(Long refundId, Long actorId, String result) {
        validateResult(result);
        RefundRow refund = requireRefund(refundId, true);
        if (result.equals(refund.status())) {
            return getRefund(refund.tenantId(), refund.id());
        }
        if (!("PENDING".equals(refund.status()) || "UNKNOWN".equals(refund.status()))) {
            throw conflict("退款已进入终态，不能改写结果");
        }
        requireCompatibleChannel(refund.tenantId(), refund.externalReference(), "REFUND", refund.currency(), refund.amountMinor(), result);

        boolean completed = "SUCCEEDED".equals(result) || "FAILED".equals(result);
        jdbc.update("""
                UPDATE mb_refund SET status = ?,
                    completed_at = CASE WHEN ? THEN UTC_TIMESTAMP(6) ELSE NULL END
                WHERE id = ? AND status IN ('PENDING', 'UNKNOWN')
                """, result, completed, refundId);
        jdbc.update("""
                UPDATE mb_channel_transaction SET status = ?, updated_at = UTC_TIMESTAMP(6)
                WHERE refund_id = ? AND transaction_type = 'REFUND'
                """, result, refundId);

        if ("SUCCEEDED".equals(result)) {
            ledger.post(refund.tenantId(), "REFUND", refund.id(), "SETTLE",
                    refund.currency(), "Simulated refund settlement", List.of(
                            entry("REVENUE", "REVENUE", "DEBIT", refund.amountMinor()),
                            entry("CASH", "ASSET", "CREDIT", refund.amountMinor())));
        }
        audit.record(refund.tenantId(), actorId, "REFUND_RESULT_" + result,
                "REFUND", refund.id().toString());
        return getRefund(refund.tenantId(), refund.id());
    }

    @Transactional(readOnly = true)
    public RefundVO getRefund(Long tenantId, Long refundId) {
        List<RefundVO> rows = jdbc.query("""
                SELECT id, tenant_id, payment_id, external_reference, currency,
                       amount_minor, status, created_at, completed_at
                FROM mb_refund WHERE tenant_id = ? AND id = ?
                """, (rs, row) -> new RefundVO(
                rs.getLong("id"), rs.getLong("tenant_id"), rs.getLong("payment_id"),
                rs.getString("external_reference"), rs.getString("currency"),
                rs.getLong("amount_minor"), rs.getString("status"),
                instant(rs.getObject("created_at", LocalDateTime.class)),
                instant(rs.getObject("completed_at", LocalDateTime.class))), tenantId, refundId);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "退款记录不存在");
        }
        return rows.getFirst();
    }

    private void requireCompatibleChannel(Long tenant,String reference,String kind,String currency,long amount,String result) {
        String observed=channelResult(tenant,reference,kind,currency,amount);
        if (observed!=null && List.of("SUCCEEDED","FAILED","REVERSED").contains(observed) && !observed.equals(result))
            throw conflict("回调与渠道终态不一致，不能覆盖已确认事实");
    }

    private InvoiceHeader requireInvoice(Long tenantId, Long invoiceId, boolean lock) {
        String sql = """
                SELECT id, tenant_id, currency, total_amount_minor, status
                FROM mb_invoice WHERE tenant_id = ? AND id = ?
                """ + (lock ? " FOR UPDATE" : "");
        List<InvoiceHeader> rows = jdbc.query(sql, (rs, row) -> new InvoiceHeader(
                rs.getLong("id"), rs.getLong("tenant_id"), rs.getString("currency"),
                rs.getLong("total_amount_minor"), rs.getString("status")), tenantId, invoiceId);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "账单不存在");
        }
        return rows.getFirst();
    }

    private PaymentRow requirePayment(Long tenantId, Long paymentId, boolean lock) {
        String sql = """
                SELECT id, tenant_id, invoice_id, request_hash, external_reference,
                       currency, amount_minor, status
                FROM mb_payment WHERE tenant_id = ? AND id = ?
                """ + (lock ? " FOR UPDATE" : "");
        List<PaymentRow> rows = jdbc.query(sql, (rs, row) -> paymentRow(rs), tenantId, paymentId);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "付款记录不存在");
        }
        return rows.getFirst();
    }

    private PaymentRow requirePayment(Long paymentId, boolean lock) {
        String sql = """
                SELECT id, tenant_id, invoice_id, request_hash, external_reference,
                       currency, amount_minor, status
                FROM mb_payment WHERE id = ?
                """ + (lock ? " FOR UPDATE" : "");
        List<PaymentRow> rows = jdbc.query(sql, (rs, row) -> paymentRow(rs), paymentId);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "付款记录不存在");
        }
        return rows.getFirst();
    }

    private PaymentRow paymentRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PaymentRow(rs.getLong("id"), rs.getLong("tenant_id"),
                rs.getLong("invoice_id"), rs.getString("request_hash"),
                rs.getString("external_reference"), rs.getString("currency"),
                rs.getLong("amount_minor"), rs.getString("status"));
    }

    private RefundRow requireRefund(Long refundId, boolean lock) {
        String sql = """
                SELECT id, tenant_id, payment_id, request_hash, external_reference,
                       currency, amount_minor, status
                FROM mb_refund WHERE id = ?
                """ + (lock ? " FOR UPDATE" : "");
        List<RefundRow> rows = jdbc.query(sql, (rs, row) -> new RefundRow(
                rs.getLong("id"), rs.getLong("tenant_id"), rs.getLong("payment_id"),
                rs.getString("request_hash"), rs.getString("external_reference"),
                rs.getString("currency"), rs.getLong("amount_minor"), rs.getString("status")), refundId);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "退款记录不存在");
        }
        return rows.getFirst();
    }

    private PaymentRow findPaymentByIdempotency(Long tenantId, String key) {
        List<PaymentRow> rows = jdbc.query("""
                SELECT id, tenant_id, invoice_id, request_hash, external_reference,
                       currency, amount_minor, status
                FROM mb_payment WHERE tenant_id = ? AND idempotency_key = ?
                """, (rs, row) -> paymentRow(rs), tenantId, key);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private PaymentRow findPaymentByIdempotencyCurrent(Long tenantId, String key) {
        List<PaymentRow> rows = jdbc.query("""
                SELECT id, tenant_id, invoice_id, request_hash, external_reference,
                       currency, amount_minor, status
                FROM mb_payment WHERE tenant_id = ? AND idempotency_key = ? FOR UPDATE
                """, (rs, row) -> paymentRow(rs), tenantId, key);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private RefundRow findRefundByIdempotency(Long tenantId, String key) {
        List<RefundRow> rows = jdbc.query("""
                SELECT id, tenant_id, payment_id, request_hash, external_reference,
                       currency, amount_minor, status
                FROM mb_refund WHERE tenant_id = ? AND idempotency_key = ?
                """, (rs, row) -> new RefundRow(
                rs.getLong("id"), rs.getLong("tenant_id"), rs.getLong("payment_id"),
                rs.getString("request_hash"), rs.getString("external_reference"),
                rs.getString("currency"), rs.getLong("amount_minor"), rs.getString("status")),
                tenantId, key);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private RefundRow findRefundByIdempotencyCurrent(Long tenantId, String key) {
        List<RefundRow> rows = jdbc.query("""
                SELECT id, tenant_id, payment_id, request_hash, external_reference,
                       currency, amount_minor, status
                FROM mb_refund WHERE tenant_id = ? AND idempotency_key = ? FOR UPDATE
                """, (rs, row) -> new RefundRow(
                rs.getLong("id"), rs.getLong("tenant_id"), rs.getLong("payment_id"),
                rs.getString("request_hash"), rs.getString("external_reference"),
                rs.getString("currency"), rs.getLong("amount_minor"), rs.getString("status")),
                tenantId, key);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private long invoiceObligation(InvoiceHeader invoice) {
        List<Long> adjustments = jdbc.query("""
                SELECT amount_minor FROM mb_invoice_adjustment
                WHERE invoice_id = ? AND status <> 'VOIDED' ORDER BY id FOR UPDATE
                """, (rs, row) -> rs.getLong(1), invoice.id());
        long total = invoice.totalAmountMinor();
        for (Long amount : adjustments) {
            total = addExact(total, amount, "账单余额计算溢出");
        }
        return total;
    }

    private long sumPayments(Long tenantId, Long invoiceId, String statusPredicate) {
        List<Long> amounts = jdbc.query("""
                SELECT amount_minor FROM mb_payment
                WHERE tenant_id = ? AND invoice_id = ? AND %s
                ORDER BY id FOR UPDATE
                """.formatted(statusPredicate),
                (rs, row) -> rs.getLong(1), tenantId, invoiceId);
        long sum = 0;
        for (Long amount : amounts) {
            sum = addExact(sum, amount, "付款金额合计超出 BIGINT 范围");
        }
        return sum;
    }

    private long sumRefunds(Long paymentId) {
        List<Long> amounts = jdbc.query("""
                SELECT amount_minor FROM mb_refund
                WHERE payment_id = ? AND status IN ('PENDING', 'UNKNOWN', 'SUCCEEDED')
                ORDER BY id FOR UPDATE
                """, (rs, row) -> rs.getLong(1), paymentId);
        long sum = 0;
        for (Long amount : amounts) {
            sum = addExact(sum, amount, "退款金额合计超出 BIGINT 范围");
        }
        return sum;
    }

    private void requireSameRequest(String actual, String requested) {
        if (!MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII),
                requested.getBytes(StandardCharsets.US_ASCII))) {
            throw conflict("幂等键已用于不同的付款或退款请求");
        }
    }

    private void validateIdempotencyKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "Idempotency-Key 必须为 1 到 128 个字符");
        }
    }

    private void validateResult(String result) {
        if (!("SUCCEEDED".equals(result) || "FAILED".equals(result) || "UNKNOWN".equals(result))) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "模拟结果不合法");
        }
    }

    private long addExact(long first, long second, String message) {
        try {
            return Math.addExact(first, second);
        } catch (ArithmeticException ex) {
            throw conflict(message);
        }
    }

    private long subtractExact(long first, long second, String message) {
        try {
            return Math.subtractExact(first, second);
        } catch (ArithmeticException ex) {
            throw conflict(message);
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

    private LedgerService.EntrySpec entry(String code, String type, String direction, long amount) {
        return new LedgerService.EntrySpec(code, type, direction, amount);
    }

    private Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.BUSINESS_CONFLICT, message);
    }

    private record InvoiceHeader(Long id, Long tenantId, String currency,
                                 Long totalAmountMinor, String status) {
    }

    private record PaymentRow(Long id, Long tenantId, Long invoiceId, String requestHash,
                              String externalReference, String currency,
                              Long amountMinor, String status) {
    }

    private record RefundRow(Long id, Long tenantId, Long paymentId, String requestHash,
                             String externalReference, String currency,
                             Long amountMinor, String status) {
    }


}

