package com.meterbill.billing.service;

import com.meterbill.common.exception.BusinessException;
import com.meterbill.common.exception.ErrorCode;
import com.meterbill.ledger.service.LedgerService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class InvoiceAccountingService {

    private final JdbcTemplate jdbc;
    private final LedgerService ledger;

    /** Join the issuing/adjusting/payment transaction; the invoice lock serializes all three. */
    @Transactional
    public void synchronize(Long tenantId, Long invoiceId) {
        var invoices = jdbc.query("""
                SELECT currency, total_amount_minor, status FROM mb_invoice
                WHERE tenant_id = ? AND id = ? FOR UPDATE
                """, (rs, row) -> new Header(rs.getString(1), rs.getLong(2), rs.getString(3)),
                tenantId, invoiceId);
        if (invoices.isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "账单不存在");
        }
        Header invoice = invoices.getFirst();
        if ("VOIDED".equals(invoice.status())) {
            return;
        }
        post(tenantId, "INVOICE", invoiceId, invoice.currency(), invoice.total());
        var adjustments = jdbc.query("""
                SELECT id, amount_minor FROM mb_invoice_adjustment
                WHERE tenant_id = ? AND invoice_id = ? AND status <> 'VOIDED'
                ORDER BY id FOR UPDATE
                """, (rs, row) -> new Adjustment(rs.getLong(1), rs.getLong(2)), tenantId, invoiceId);
        long obligation = invoice.total();
        for (Adjustment adjustment : adjustments) {
            post(tenantId, "INVOICE_ADJUSTMENT", adjustment.id(), invoice.currency(), adjustment.amount());
            obligation = add(obligation, adjustment.amount());
        }
        // Current reads are essential after waiting for a concurrent payment's invoice lock.
        var payments = jdbc.query("""
                SELECT amount_minor FROM mb_payment
                WHERE tenant_id = ? AND invoice_id = ? AND status = 'SUCCEEDED'
                ORDER BY id FOR UPDATE
                """, (rs, row) -> rs.getLong(1), tenantId, invoiceId);
        long paid = 0;
        for (Long payment : payments) {
            paid = add(paid, payment);
        }
        String status = obligation <= 0 || paid >= obligation ? "PAID"
                : paid > 0 ? "PARTIALLY_PAID" : "ISSUED";
        jdbc.update("UPDATE mb_invoice SET status = ? WHERE tenant_id = ? AND id = ?",
                status, tenantId, invoiceId);
    }

    private void post(Long tenantId, String type, Long id, String currency, long amount) {
        if (amount == 0) {
            return;
        }
        long magnitude;
        try {
            magnitude = amount > 0 ? amount : Math.negateExact(amount);
        } catch (ArithmeticException ex) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "账务金额超出 BIGINT 范围");
        }
        ledger.post(tenantId, type, id, "ISSUE", currency, "Invoice accounting", List.of(
                new LedgerService.EntrySpec("AR", "ASSET", amount > 0 ? "DEBIT" : "CREDIT", magnitude),
                new LedgerService.EntrySpec("REVENUE", "REVENUE", amount > 0 ? "CREDIT" : "DEBIT", magnitude)));
    }

    private long add(long first, long second) {
        try {
            return Math.addExact(first, second);
        } catch (ArithmeticException ex) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "账单金额超出 BIGINT 范围");
        }
    }

    private record Header(String currency, long total, String status) { }
    private record Adjustment(Long id, long amount) { }
}
