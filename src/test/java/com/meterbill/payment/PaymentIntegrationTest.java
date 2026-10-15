package com.meterbill.payment;

import com.meterbill.billing.service.InvoiceService;
import com.meterbill.billing.web.vo.InvoiceVO;
import com.meterbill.common.exception.BusinessException;
import com.meterbill.ledger.service.LedgerService;
import com.meterbill.payment.service.PaymentService;
import com.meterbill.payment.web.vo.PaymentVO;
import com.meterbill.payment.web.vo.RefundVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class PaymentIntegrationTest {

    @Container
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4")
            .withDatabaseName("meterbill_payment_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private InvoiceService invoices;

    @Autowired
    private PaymentService payments;

    @Autowired
    private LedgerService ledger;

    @Test
    void paymentAndRefundCallbacksAreIdempotentAndEveryPostingBalances() throws Exception {
        Fixture fixture = createInvoice();
        PaymentVO first = payments.createPayment(fixture.tenantId(), fixture.invoiceId(),
                "pay-first-" + fixture.suffix(), 600L, null);
        PaymentVO repeated = payments.createPayment(fixture.tenantId(), fixture.invoiceId(),
                "pay-first-" + fixture.suffix(), 600L, null);
        assertEquals(first.id(), repeated.id());
        assertEquals("PENDING", first.status());
        assertThrows(BusinessException.class, () -> payments.createPayment(
                fixture.tenantId(), fixture.invoiceId(), "pay-first-" + fixture.suffix(), 601L, null));

        PaymentVO unknown = payments.simulatePaymentResult(first.id(), null, "UNKNOWN");
        assertEquals("UNKNOWN", unknown.status());
        PaymentVO succeeded = payments.simulatePaymentResult(first.id(), null, "SUCCEEDED");
        assertEquals("SUCCEEDED", succeeded.status());
        payments.simulatePaymentResult(first.id(), null, "SUCCEEDED");
        assertThrows(BusinessException.class,
                () -> payments.simulatePaymentResult(first.id(), null, "FAILED"));
        assertEquals("PARTIALLY_PAID", invoiceStatus(fixture.invoiceId()));

        PaymentVO second = payments.createPayment(fixture.tenantId(), fixture.invoiceId(),
                "pay-second-" + fixture.suffix(), 400L, null);
        payments.simulatePaymentResult(second.id(), null, "SUCCEEDED");
        assertEquals("PAID", invoiceStatus(fixture.invoiceId()));

        RefundVO refund = payments.requestRefund(fixture.tenantId(), first.id(),
                "refund-" + fixture.suffix(), 500L, null);
        RefundVO refundReplay = payments.requestRefund(fixture.tenantId(), first.id(),
                "refund-" + fixture.suffix(), 500L, null);
        assertEquals(refund.id(), refundReplay.id());
        assertThrows(BusinessException.class, () -> payments.requestRefund(
                fixture.tenantId(), first.id(), "too-much-" + fixture.suffix(), 101L, null));
        assertEquals("UNKNOWN", payments.simulateRefundResult(refund.id(), null, "UNKNOWN").status());
        assertEquals("SUCCEEDED", payments.simulateRefundResult(refund.id(), null, "SUCCEEDED").status());
        payments.simulateRefundResult(refund.id(), null, "SUCCEEDED");

        assertEquals(2, jdbc.queryForObject("""
                SELECT COUNT(*) FROM mb_ledger_transaction
                WHERE tenant_id = ? AND business_type = 'PAYMENT'
                """, Integer.class, fixture.tenantId()));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM mb_ledger_transaction
                WHERE tenant_id = ? AND business_type = 'REFUND'
                """, Integer.class, fixture.tenantId()));
        assertEquals(3, jdbc.queryForObject("""
                SELECT COUNT(*) FROM mb_channel_transaction WHERE tenant_id = ?
                """, Integer.class, fixture.tenantId()));
        assertTrue(ledger.isBalanced(fixture.tenantId()));
        assertEquals(0L, jdbc.queryForObject("""
                SELECT SUM(debit_total_minor - credit_total_minor)
                FROM mb_ledger_transaction WHERE tenant_id = ?
                """, Long.class, fixture.tenantId()));
    }

    @Test
    void concurrentRefundRequestsCannotExceedTheSuccessfulPayment() throws Exception {
        Fixture fixture = createInvoice();
        PaymentVO payment = payments.createPayment(fixture.tenantId(), fixture.invoiceId(),
                "pay-concurrent-" + fixture.suffix(), 600L, null);
        payments.simulatePaymentResult(payment.id(), null, "SUCCEEDED");

        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> requests = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                String key = "concurrent-refund-" + index + "-" + fixture.suffix();
                requests.add(workers.submit(() -> {
                    start.await();
                    try {
                        payments.requestRefund(fixture.tenantId(), payment.id(), key, 400L, null);
                        return true;
                    } catch (BusinessException ex) {
                        return false;
                    }
                }));
            }
            start.countDown();
            int accepted = 0;
            for (Future<Boolean> request : requests) {
                accepted += request.get(15, TimeUnit.SECONDS) ? 1 : 0;
            }
            assertEquals(1, accepted);
            assertEquals(1, jdbc.queryForObject("""
                    SELECT COUNT(*) FROM mb_refund WHERE payment_id = ?
                    """, Integer.class, payment.id()));
            assertEquals(400L, jdbc.queryForObject("""
                    SELECT SUM(amount_minor) FROM mb_refund WHERE payment_id = ?
                    """, Long.class, payment.id()));
            assertTrue(ledger.isBalanced(fixture.tenantId()));
        } catch (ExecutionException ex) {
            throw new AssertionError("Concurrent refund request failed unexpectedly", ex.getCause());
        } finally {
            workers.shutdownNow();
        }
    }

    @Autowired private com.meterbill.payment.service.SimulatedChannelService channel;
    @Autowired private com.meterbill.operations.service.ReconciliationService reconciliation;

    @Test
    void lostCallbacksRecoverFromIndependentChannelObservationAndMissingStaysUnknown() throws Exception {
        Fixture f=createInvoice();
        var p=payments.createPayment(f.tenantId(),f.invoiceId(),"query",600L,null);
        payments.simulatePaymentResult(p.id(),null,"UNKNOWN");
        channel.observe(f.tenantId(),p.externalReference(),"CHARGE","CNY",600,"SUCCEEDED",101L);
        assertEquals("UNKNOWN",payments.getPayment(f.tenantId(),p.id()).status());
        assertThrows(BusinessException.class,()->payments.simulatePaymentResult(p.id(),null,"FAILED"));
        assertTrue(reconciliation.run(f.tenantId(),"before-query",null).differences().stream()
                .anyMatch(d->d.differenceType().equals("CHANNEL_TRANSACTION_MISMATCH")));
        assertEquals("SUCCEEDED",payments.queryPaymentChannel(p.id(),101L).status());
        payments.queryPaymentChannel(p.id(),101L);
        var r=payments.requestRefund(f.tenantId(),p.id(),"refund-query",100L,null);
        payments.simulateRefundResult(r.id(),null,"UNKNOWN");
        channel.observe(f.tenantId(),r.externalReference(),"REFUND","CNY",100,"SUCCEEDED",101L);
        assertEquals("SUCCEEDED",payments.queryRefundChannel(r.id(),101L).status());
        assertTrue(reconciliation.run(f.tenantId(),"after-query",null).differences().isEmpty());
        var missing=payments.createPayment(f.tenantId(),f.invoiceId(),"missing",100L,null);
        payments.simulatePaymentResult(missing.id(),null,"UNKNOWN");
        jdbc.update("DELETE FROM mb_channel_transaction WHERE payment_id=?",missing.id());
        assertEquals("UNKNOWN",payments.queryPaymentChannel(missing.id(),101L).status());
        channel.observe(f.tenantId(),missing.externalReference(),"CHARGE","CNY",101,"SUCCEEDED",101L);
        assertThrows(BusinessException.class,()->payments.queryPaymentChannel(missing.id(),101L));
    }

    @Test
    void reversalIsAtomicIdempotentRestoresReceivableAndDisallowsRefunds() throws Exception {
        Fixture f=createInvoice();
        var p=payments.createPayment(f.tenantId(),f.invoiceId(),"reverse",1000L,null);
        payments.simulatePaymentResult(p.id(),null,"SUCCEEDED");
        assertEquals("PAID",invoiceStatus(f.invoiceId()));
        jdbc.update("UPDATE mb_channel_transaction SET amount_minor=999 WHERE payment_id=?",p.id());
        assertThrows(BusinessException.class,()->payments.reversePayment(p.id(),101L,"Wrong simulated receipt"));
        jdbc.update("UPDATE mb_channel_transaction SET amount_minor=1000 WHERE payment_id=?",p.id());
        Long reversal=payments.reversePayment(p.id(),101L,"Wrong simulated receipt");
        assertEquals(reversal,payments.reversePayment(p.id(),101L,"Wrong simulated receipt"));
        assertEquals("REVERSED",payments.getPayment(f.tenantId(),p.id()).status());
        assertEquals("ISSUED",invoiceStatus(f.invoiceId()));
        assertTrue(ledger.isBalanced(f.tenantId()));
        assertTrue(reconciliation.run(f.tenantId(),"reversed",null).differences().isEmpty());
        assertThrows(BusinessException.class,()->payments.requestRefund(f.tenantId(),p.id(),"no-refund",1L,null));
        assertThrows(BusinessException.class,()->payments.simulatePaymentResult(p.id(),null,"SUCCEEDED"));
        assertThrows(BusinessException.class,()->payments.reversePayment(p.id(),101L,"different reason"));
        var second=payments.createPayment(f.tenantId(),f.invoiceId(),"repay",1000L,null);
        payments.simulatePaymentResult(second.id(),null,"SUCCEEDED");
        var refund=payments.requestRefund(f.tenantId(),second.id(),"blocks",1L,null);
        assertThrows(BusinessException.class,()->payments.reversePayment(second.id(),101L,"blocked"));
        payments.simulateRefundResult(refund.id(),null,"UNKNOWN");
        assertThrows(BusinessException.class,()->payments.reversePayment(second.id(),101L,"blocked"));
        payments.simulateRefundResult(refund.id(),null,"FAILED");
        jdbc.update("UPDATE mb_ledger_account SET status='CLOSED' WHERE tenant_id=? AND account_code='AR'",f.tenantId());
        assertThrows(BusinessException.class,()->payments.reversePayment(second.id(),101L,"rollback"));
        assertEquals("SUCCEEDED",payments.getPayment(f.tenantId(),second.id()).status());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mb_payment_reversal WHERE payment_id=?",Integer.class,second.id()));
    }

    @Test
    void perReferenceReconciliationDetectsOffsettingAmountsAndBothOneSidedCases() throws Exception {
        Fixture f=createInvoice();
        var a=payments.createPayment(f.tenantId(),f.invoiceId(),"a",400L,null);
        var b=payments.createPayment(f.tenantId(),f.invoiceId(),"b",400L,null);
        payments.simulatePaymentResult(a.id(),null,"SUCCEEDED");
        payments.simulatePaymentResult(b.id(),null,"SUCCEEDED");
        // Deliberate isolated corrupt fixture: totals still match, individual facts do not.
        jdbc.update("UPDATE mb_channel_transaction SET amount_minor=amount_minor+10 WHERE payment_id=?",a.id());
        jdbc.update("UPDATE mb_channel_transaction SET amount_minor=amount_minor-10 WHERE payment_id=?",b.id());
        var diffs=reconciliation.run(f.tenantId(),"offset",null).differences();
        assertEquals(2,diffs.stream().filter(d->d.differenceType().equals("CHANNEL_TRANSACTION_MISMATCH")).count());
        assertEquals(0,diffs.stream().filter(d->d.differenceType().equals("CHANNEL_CASH_MISMATCH")).count());
        jdbc.update("DELETE FROM mb_channel_transaction WHERE payment_id=?",a.id());
        channel.observe(f.tenantId(),"orphan-"+f.suffix(),"CHARGE","CNY",10,"SUCCEEDED",101L);
        var unilateral=reconciliation.run(f.tenantId(),"unilateral",null).differences();
        assertTrue(unilateral.stream().anyMatch(d->d.differenceType().equals("CHANNEL_MISSING")));
        assertTrue(unilateral.stream().anyMatch(d->d.differenceType().equals("INTERNAL_ORDER_MISSING")));
    }
    @Test
    void concurrentRefundAndReversalCannotBothSucceed() throws Exception {
        Fixture f=createInvoice();
        var p=payments.createPayment(f.tenantId(),f.invoiceId(),"race-reversal",1000L,null);
        payments.simulatePaymentResult(p.id(),null,"SUCCEEDED");
        CountDownLatch start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var reverse=pool.submit(()->{start.await();try{payments.reversePayment(p.id(),101L,"race");return true;}catch(BusinessException e){return false;}});
            var refund=pool.submit(()->{start.await();try{payments.requestRefund(f.tenantId(),p.id(),"race-refund",100L,null);return true;}catch(BusinessException e){return false;}});
            start.countDown();
            assertEquals(1,(reverse.get(10,TimeUnit.SECONDS)?1:0)+(refund.get(10,TimeUnit.SECONDS)?1:0));
            assertTrue(ledger.isBalanced(f.tenantId()));
            assertTrue(reconciliation.run(f.tenantId(),"race",null).differences().isEmpty());
        }
    }
    private Fixture createInvoice() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO mb_tenant(tenant_code, name) VALUES (?, ?)",
                "payment-" + suffix, "Payment Test");
        Long tenantId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO mb_product(product_code, name, status) VALUES (?, ?, 'ACTIVE')",
                "payment-product-" + suffix, "Payment Product");
        Long productId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO mb_plan(product_id, plan_code, name, status)
                VALUES (?, ?, 'Payment Plan', 'ACTIVE')
                """, productId, "payment-plan-" + suffix);
        Long planId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO mb_price(plan_id, currency, billing_period) VALUES (?, 'CNY', 'MONTHLY')",
                planId);
        Long priceId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO mb_price_version
                    (price_id, version_no, base_amount_minor, status, effective_at)
                VALUES (?, 1, 1000, 'PUBLISHED', ?)
                """, priceId, LocalDateTime.ofInstant(Instant.now().minusSeconds(120L * 86400), ZoneOffset.UTC));
        Long versionId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO mb_usage_meter(product_id, meter_code, name, unit)
                VALUES (?, 'input_tokens', 'Input Tokens', 'token')
                """, productId);
        Long meterId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO mb_usage_price_rule
                    (price_version_id, meter_id, pricing_mode, included_quantity, unit_amount_minor)
                VALUES (?, ?, 'PER_UNIT', 0, 1)
                """, versionId, meterId);
        jdbc.update("""
                INSERT INTO mb_subscription(tenant_id, plan_id, idempotency_key, request_hash, status)
                VALUES (?, ?, ?, ?, 'ACTIVE')
                """, tenantId, planId, "payment-" + suffix, "0".repeat(64));
        Long subscriptionId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        Instant now = Instant.now();
        LocalDateTime start = LocalDateTime.ofInstant(now.minusSeconds(90L * 86400), ZoneOffset.UTC);
        LocalDateTime end = LocalDateTime.ofInstant(now.minusSeconds(60L * 86400), ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO mb_subscription_period
                    (tenant_id, subscription_id, plan_id, price_id, price_version_id,
                     currency, base_amount_minor, active_from, period_start, period_end, status)
                VALUES (?, ?, ?, ?, ?, 'CNY', 1000, ?, ?, ?, 'ENDED')
                """, tenantId, subscriptionId, planId, priceId, versionId, start, start, end);
        Long periodId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        InvoiceVO invoice = invoices.issuePeriod(periodId);
        return new Fixture(tenantId, invoice.id(), suffix);
    }

    private String invoiceStatus(Long invoiceId) {
        return jdbc.queryForObject("SELECT status FROM mb_invoice WHERE id = ?", String.class, invoiceId);
    }

    private record Fixture(Long tenantId, Long invoiceId, String suffix) {
    }
}
