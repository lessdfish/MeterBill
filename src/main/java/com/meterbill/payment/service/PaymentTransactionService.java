package com.meterbill.payment.service;

import com.meterbill.common.exception.BusinessException;
import com.meterbill.common.exception.ErrorCode;
import com.meterbill.payment.web.vo.PaymentTransactionVO;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PaymentTransactionService {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public void appendPayment(Long tenantId, Long accountId, Long paymentId,
                              String type, String status, String externalReference,
                              String currency, long amountMinor, String eventKey) {
        int sequence = nextSequence("payment_id", paymentId);
        jdbc.update("""
                INSERT IGNORE INTO mb_payment_transaction
                    (tenant_id, account_id, payment_id, sequence_no, transaction_type,
                     status, external_reference, currency, amount_minor, event_key, effective_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, tenantId, accountId, paymentId, sequence, type, status,
                externalReference, currency, amountMinor, eventKey, Timestamp.from(clock.instant()));
    }

    public void appendRefund(Long tenantId, Long accountId, Long refundId,
                             String status, String externalReference,
                             String currency, long amountMinor, String eventKey) {
        int sequence = nextSequence("refund_id", refundId);
        jdbc.update("""
                INSERT IGNORE INTO mb_payment_transaction
                    (tenant_id, account_id, refund_id, sequence_no, transaction_type,
                     status, external_reference, currency, amount_minor, event_key, effective_at)
                VALUES (?, ?, ?, ?, 'REFUND', ?, ?, ?, ?, ?, ?)
                """, tenantId, accountId, refundId, sequence, status,
                externalReference, currency, amountMinor, eventKey, Timestamp.from(clock.instant()));
    }

    public List<PaymentTransactionVO> forPayment(Long tenantId, Long paymentId) {
        requireOwner("mb_payment", tenantId, paymentId);
        return list("payment_id", tenantId, paymentId);
    }

    public List<PaymentTransactionVO> forRefund(Long tenantId, Long refundId) {
        requireOwner("mb_refund", tenantId, refundId);
        return list("refund_id", tenantId, refundId);
    }

    private int nextSequence(String ownerColumn, Long ownerId) {
        Integer value = jdbc.queryForObject("SELECT COALESCE(MAX(sequence_no), 0) + 1 FROM mb_payment_transaction WHERE "
                + ownerColumn + " = ?", Integer.class, ownerId);
        return value == null ? 1 : value;
    }

    private List<PaymentTransactionVO> list(String ownerColumn, Long tenantId, Long ownerId) {
        return jdbc.query("""
                SELECT id, account_id, payment_id, refund_id, sequence_no, transaction_type,
                       status, external_reference, currency, amount_minor, effective_at
                FROM mb_payment_transaction WHERE tenant_id = ? AND %s = ? ORDER BY sequence_no, id
                """.formatted(ownerColumn), (rs, n) -> new PaymentTransactionVO(
                rs.getLong("id"), rs.getLong("account_id"),
                (Long) rs.getObject("payment_id"), (Long) rs.getObject("refund_id"),
                rs.getInt("sequence_no"), rs.getString("transaction_type"), rs.getString("status"),
                rs.getString("external_reference"), rs.getString("currency"),
                rs.getLong("amount_minor"), rs.getObject("effective_at", LocalDateTime.class)
                .toInstant(ZoneOffset.UTC)), tenantId, ownerId);
    }

    private void requireOwner(String table, Long tenantId, Long id) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE tenant_id = ? AND id = ?",
                Integer.class, tenantId, id);
        if (count == null || count != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "支付交易不存在");
        }
    }
}
