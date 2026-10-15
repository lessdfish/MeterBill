package com.meterbill.ledger.service;

import com.meterbill.common.exception.BusinessException;
import com.meterbill.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

@Service
@RequiredArgsConstructor
public class LedgerService {

    private final JdbcTemplate jdbc;

    @Transactional
    public Long post(Long tenantId, String businessType, Long businessId,
                     String postingAction, String currency, String description,
                     List<EntrySpec> requestedEntries) {
        List<EntrySpec> entries = requestedEntries.stream()
                .sorted(Comparator.comparing(EntrySpec::accountCode)
                        .thenComparing(EntrySpec::direction))
                .toList();
        if (entries.isEmpty()) {
            throw invalid("账务分录不能为空");
        }
        long debit = 0;
        long credit = 0;
        for (EntrySpec entry : entries) {
            if (entry.amountMinor() <= 0
                    || !("DEBIT".equals(entry.direction()) || "CREDIT".equals(entry.direction()))) {
                throw invalid("账务方向和金额不合法");
            }
            if ("DEBIT".equals(entry.direction())) {
                debit = addExact(debit, entry.amountMinor());
            } else {
                credit = addExact(credit, entry.amountMinor());
            }
        }
        if (debit == 0 || debit != credit) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT,
                    "账务分录借贷金额必须相等且大于零");
        }

        String requestHash = requestHash(tenantId, businessType, businessId,
                postingAction, currency, entries);
        Long existing = findTransaction(tenantId, businessType, businessId, postingAction);
        if (existing != null) {
            requireSameRequest(existing, requestHash);
            return existing;
        }

        try {
            jdbc.update("""
                    INSERT INTO mb_ledger_transaction
                        (tenant_id, business_type, business_id, posting_action, currency,
                         description, debit_total_minor, credit_total_minor, request_hash, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'POSTED')
                    """, tenantId, businessType, businessId, postingAction, currency,
                    description, debit, credit, requestHash);
        } catch (DuplicateKeyException ex) {
            Long raced = findTransaction(tenantId, businessType, businessId, postingAction);
            if (raced == null) {
                throw ex;
            }
            requireSameRequest(raced, requestHash);
            return raced;
        }
        Long transactionId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        for (EntrySpec entry : entries) {
            Long accountId = ensureAccount(tenantId, entry.accountCode(),
                    entry.accountType(), currency);
            jdbc.update("""
                    INSERT INTO mb_ledger_entry
                        (transaction_id, account_id, direction, amount_minor)
                    VALUES (?, ?, ?, ?)
                    """, transactionId, accountId, entry.direction(), entry.amountMinor());
        }
        return transactionId;
    }

    @Transactional(readOnly = true)
    public boolean isBalanced(Long tenantId) {
        Integer unbalanced = jdbc.queryForObject("""
                SELECT COUNT(*) FROM mb_ledger_transaction t
                WHERE t.tenant_id = ? AND (
                    t.debit_total_minor <> t.credit_total_minor
                    OR t.debit_total_minor <> (
                        SELECT COALESCE(SUM(e.amount_minor), 0) FROM mb_ledger_entry e
                        WHERE e.transaction_id = t.id AND e.direction = 'DEBIT'
                    )
                    OR t.credit_total_minor <> (
                        SELECT COALESCE(SUM(e.amount_minor), 0) FROM mb_ledger_entry e
                        WHERE e.transaction_id = t.id AND e.direction = 'CREDIT'
                    )
                )
                """, Integer.class, tenantId);
        return unbalanced != null && unbalanced == 0;
    }

    private Long ensureAccount(Long tenantId, String code, String type, String currency) {
        jdbc.update("""
                INSERT IGNORE INTO mb_ledger_account
                    (tenant_id, account_code, account_type, currency, status)
                VALUES (?, ?, ?, ?, 'ACTIVE')
                """, tenantId, code, type, currency);
        List<Long> rows = jdbc.query("""
                SELECT id FROM mb_ledger_account
                WHERE tenant_id = ? AND account_code = ? AND currency = ? AND status = 'ACTIVE' FOR UPDATE
                """, (rs, row) -> rs.getLong(1), tenantId, code, currency);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT,
                    "账务科目不可用: " + code);
        }
        return rows.getFirst();
    }

    private Long findTransaction(Long tenantId, String businessType,
                                 Long businessId, String postingAction) {
        List<Long> rows = jdbc.query("""
                SELECT id FROM mb_ledger_transaction
                WHERE tenant_id = ? AND business_type = ? AND business_id = ? AND posting_action = ? FOR UPDATE
                """, (rs, row) -> rs.getLong(1), tenantId,
                businessType, businessId, postingAction);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void requireSameRequest(Long transactionId, String requestHash) {
        String existingHash = jdbc.queryForObject("""
                SELECT request_hash FROM mb_ledger_transaction WHERE id = ? FOR UPDATE
                """, String.class, transactionId);
        if (!MessageDigest.isEqual(existingHash.getBytes(StandardCharsets.US_ASCII),
                requestHash.getBytes(StandardCharsets.US_ASCII))) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT,
                    "该业务账务动作已使用不同金额或分录入账");
        }
    }

    private String requestHash(Long tenantId, String businessType, Long businessId,
                               String action, String currency, List<EntrySpec> entries) {
        StringBuilder canonical = new StringBuilder()
                .append(tenantId).append('|').append(businessType).append('|')
                .append(businessId).append('|').append(action).append('|').append(currency);
        for (EntrySpec entry : entries) {
            canonical.append('|').append(entry.accountCode()).append(':')
                    .append(entry.accountType()).append(':').append(entry.direction())
                    .append(':').append(entry.amountMinor());
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private long addExact(long first, long second) {
        try {
            return Math.addExact(first, second);
        } catch (ArithmeticException ex) {
            throw invalid("账务金额超出 BIGINT 范围");
        }
    }

    private BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.INVALID_ARGUMENT, message);
    }

    public record EntrySpec(String accountCode, String accountType,
                            String direction, long amountMinor) {
    }
}
