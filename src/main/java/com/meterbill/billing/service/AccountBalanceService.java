package com.meterbill.billing.service;

import com.meterbill.account.api.AccountQueryApi;
import com.meterbill.billing.api.AccountBalanceApi;
import com.meterbill.common.exception.BusinessException;
import com.meterbill.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AccountBalanceService implements AccountBalanceApi {

    private final JdbcTemplate jdbc;
    private final AccountQueryApi accounts;

    @Override
    @Transactional(readOnly = true)
    public AccountBalance balance(Long tenantId, Long accountId) {
        AccountQueryApi.AccountRef account = accounts.require(tenantId, accountId);
        List<String> currencies = jdbc.query("""
                SELECT DISTINCT currency FROM mb_invoice
                WHERE tenant_id = ? AND account_id = ? AND status <> 'VOIDED'
                """, (rs, n) -> rs.getString(1), tenantId, accountId);
        if (currencies.size() > 1 || (!currencies.isEmpty() && !account.currency().equals(currencies.getFirst()))) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "客户账户存在不一致币种");
        }
        long invoiced = value("""
                SELECT COALESCE(SUM(total_amount_minor), 0) FROM mb_invoice
                WHERE tenant_id = ? AND account_id = ? AND status <> 'VOIDED'
                """, tenantId, accountId);
        long adjusted = value("""
                SELECT COALESCE(SUM(a.amount_minor), 0)
                FROM mb_invoice_adjustment a JOIN mb_invoice i ON i.id = a.invoice_id
                WHERE i.tenant_id = ? AND i.account_id = ? AND a.status <> 'VOIDED'
                """, tenantId, accountId);
        long paid = value("""
                SELECT COALESCE(SUM(amount_minor), 0) FROM mb_payment
                WHERE tenant_id = ? AND account_id = ? AND status = 'SUCCEEDED'
                """, tenantId, accountId);
        long refunded = value("""
                SELECT COALESCE(SUM(amount_minor), 0) FROM mb_refund
                WHERE tenant_id = ? AND account_id = ? AND status = 'SUCCEEDED'
                """, tenantId, accountId);
        try {
            long balance = Math.addExact(Math.subtractExact(Math.addExact(invoiced, adjusted), paid), refunded);
            return new AccountBalance(accountId, account.currency(), invoiced, adjusted, paid, refunded, balance);
        } catch (ArithmeticException ex) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "客户余额超出 BIGINT 范围");
        }
    }

    private long value(String sql, Long tenantId, Long accountId) {
        Long value = jdbc.queryForObject(sql, Long.class, tenantId, accountId);
        return value == null ? 0 : value;
    }
}
