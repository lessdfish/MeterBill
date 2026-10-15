package com.meterbill.billing;

import com.meterbill.billing.service.InvoiceService;
import com.meterbill.billing.web.vo.InvoiceAdjustmentVO;
import com.meterbill.billing.web.vo.InvoiceVO;
import com.meterbill.common.exception.BusinessException;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class InvoiceIntegrationTest {

    @Container
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4")
            .withDatabaseName("meterbill_billing_test")
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

    @Test
    void issuesOneDeterministicInvoiceWithItsPublishedPriceSnapshot() throws Exception {
        Fixture fixture = createFixture("GRADUATED", 100, null,
                List.of(new Tier(10L, 2), new Tier(null, 1)));
        recordProcessedEvents(fixture, 50, 75);

        InvoiceVO invoice = invoices.issuePeriod(fixture.periodId());
        InvoiceVO repeated = invoices.issuePeriod(fixture.periodId());

        assertEquals(invoice.id(), repeated.id());
        assertEquals(5_000L, invoice.baseAmountMinor());
        assertEquals(35L, invoice.usageAmountMinor());
        assertEquals(5_035L, invoice.totalAmountMinor());
        assertEquals(fixture.priceVersionId(), invoice.priceVersionId());
        assertEquals("ISSUED", invoice.status());
        assertEquals(2, invoice.lines().size());
        assertEquals(125L, invoice.lines().get(1).quantity());
        assertEquals(25L, invoice.lines().get(1).billableQuantity());
        assertEquals(35L, invoice.lines().get(1).amountMinor());
        assertEquals(1, countForPeriod("mb_invoice", fixture.periodId()));
        assertEquals(2, countForPeriod("mb_invoice_event", fixture.periodId()));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM mb_invoice_line WHERE invoice_id = ?",
                Integer.class, invoice.id()));
        assertNotNull(invoice.lines().get(1).rateSnapshot());
    }

    @Test
    void lateUsageCreatesAnAdjustmentWithoutRewritingTheIssuedInvoice() throws Exception {
        Fixture fixture = createFixture("PER_UNIT", 10, 3L, List.of());
        recordProcessedEvents(fixture, 20);
        InvoiceVO invoice = invoices.issuePeriod(fixture.periodId());
        assertEquals(5_030L, invoice.totalAmountMinor());

        recordProcessedEvents(fixture, 5);
        InvoiceAdjustmentVO adjustment = invoices.adjustLateUsage(fixture.periodId());
        InvoiceVO unchanged = invoices.getInvoice(fixture.tenantId(), invoice.id());

        assertEquals(15L, adjustment.amountMinor());
        assertEquals(1, adjustment.sourceEventCount());
        assertEquals(5L, adjustment.lines().getFirst().quantity());
        assertEquals(5L, adjustment.lines().getFirst().billableQuantity());
        assertEquals(5_030L, unchanged.totalAmountMinor());
        assertEquals(1, unchanged.adjustments().size());
        assertEquals(adjustment.id(), unchanged.adjustments().getFirst().id());
        assertEquals(1, countForPeriod("mb_invoice", fixture.periodId()));
        assertEquals(1, countForPeriod("mb_invoice_adjustment", fixture.periodId()));
        assertThrows(BusinessException.class,
                () -> invoices.adjustLateUsage(fixture.periodId()));
    }

    @Test
    void invoiceCannotOmitAnUnprocessedUsageEvent() throws Exception {
        Fixture fixture = createFixture("PER_UNIT", 0, 1L, List.of());
        insertEvent(fixture, 19, "RECEIVED", false);

        assertThrows(BusinessException.class, () -> invoices.issuePeriod(fixture.periodId()));
        assertEquals(0, countForPeriod("mb_invoice", fixture.periodId()));
    }

    private Fixture createFixture(String mode, long included, Long unitAmount,
                                  List<Tier> tiers) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO mb_tenant(tenant_code, name) VALUES (?, ?)",
                "billing-" + suffix, "Billing Test");
        Long tenantId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO mb_product(product_code, name, status) VALUES (?, ?, 'ACTIVE')",
                "billing-product-" + suffix, "Billing Product");
        Long productId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO mb_plan(product_id, plan_code, name, status)
                VALUES (?, ?, 'Billing Plan', 'ACTIVE')
                """, productId, "billing-plan-" + suffix);
        Long planId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO mb_price(plan_id, currency, billing_period) VALUES (?, 'CNY', 'MONTHLY')",
                planId);
        Long priceId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO mb_price_version
                    (price_id, version_no, base_amount_minor, status, effective_at)
                VALUES (?, 1, 5000, 'PUBLISHED', ?)
                """, priceId, LocalDateTime.ofInstant(Instant.now().minusSeconds(120 * 86400), ZoneOffset.UTC));
        Long versionId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO mb_usage_meter(product_id, meter_code, name, unit)
                VALUES (?, 'input_tokens', 'Input Tokens', 'token')
                """, productId);
        Long meterId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO mb_usage_price_rule
                    (price_version_id, meter_id, pricing_mode, included_quantity, unit_amount_minor)
                VALUES (?, ?, ?, ?, ?)
                """, versionId, meterId, mode, included, unitAmount);
        Long ruleId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        for (int index = 0; index < tiers.size(); index++) {
            Tier tier = tiers.get(index);
            jdbc.update("""
                    INSERT INTO mb_usage_price_tier(rule_id, tier_no, up_to_quantity, unit_amount_minor)
                    VALUES (?, ?, ?, ?)
                    """, ruleId, index + 1, tier.upToQuantity(), tier.unitAmountMinor());
        }

        jdbc.update("""
                INSERT INTO mb_subscription(tenant_id, plan_id, idempotency_key, request_hash, status)
                VALUES (?, ?, ?, ?, 'ACTIVE')
                """, tenantId, planId, "billing-" + suffix, "0".repeat(64));
        Long subscriptionId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        Instant now = Instant.now();
        LocalDateTime start = LocalDateTime.ofInstant(now.minusSeconds(90 * 86400), ZoneOffset.UTC);
        LocalDateTime end = LocalDateTime.ofInstant(now.minusSeconds(60 * 86400), ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO mb_subscription_period
                    (tenant_id, subscription_id, plan_id, price_id, price_version_id,
                     currency, base_amount_minor, active_from, period_start, period_end, status)
                VALUES (?, ?, ?, ?, ?, 'CNY', 5000, ?, ?, ?, 'ENDED')
                """, tenantId, subscriptionId, planId, priceId, versionId,
                start, start, end);
        Long periodId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return new Fixture(tenantId, subscriptionId, periodId, versionId, meterId,
                LocalDateTime.ofInstant(start.toInstant(ZoneOffset.UTC).plusSeconds(1), ZoneOffset.UTC));
    }

    private void recordProcessedEvents(Fixture fixture, long... quantities) throws Exception {
        for (long quantity : quantities) {
            insertEvent(fixture, quantity, "PROCESSED", true);
        }
        Long total = jdbc.queryForObject("""
                SELECT COALESCE(SUM(quantity), 0) FROM mb_usage_event
                WHERE period_id = ? AND status = 'PROCESSED'
                """, Long.class, fixture.periodId());
        jdbc.update("""
                INSERT INTO mb_usage_period_total
                    (tenant_id, subscription_id, period_id, meter_id, total_quantity, revision)
                VALUES (?, ?, ?, ?, ?, 1)
                ON DUPLICATE KEY UPDATE total_quantity = VALUES(total_quantity), revision = revision + 1
                """, fixture.tenantId(), fixture.subscriptionId(), fixture.periodId(),
                fixture.meterId(), total);
    }

    private void insertEvent(Fixture fixture, long quantity, String status,
                             boolean markProcessed) throws Exception {
        String eventId = "evt-" + UUID.randomUUID().toString().replace("-", "");
        LocalDateTime occurredAt = fixture.occurredAt().plusSeconds(quantity);
        jdbc.update("""
                INSERT INTO mb_usage_event
                    (tenant_id, subscription_id, period_id, meter_id, source_id, event_id,
                     model_code, quantity, occurred_at, payload_hash, status,
                     delivery_count, processed_at)
                VALUES (?, ?, ?, ?, 'billing-test', ?, 'model-test', ?, ?, ?, ?, 1,
                        IF(? = 'PROCESSED', UTC_TIMESTAMP(6), NULL))
                """, fixture.tenantId(), fixture.subscriptionId(), fixture.periodId(),
                fixture.meterId(), eventId, quantity, occurredAt,
                "a".repeat(64), status, status);
        Long eventIdDb = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        if (markProcessed) {
            jdbc.update("INSERT INTO mb_usage_event_processed(usage_event_id) VALUES (?)", eventIdDb);
        }
    }

    private int countForPeriod(String table, Long periodId) {
        if ("mb_invoice_event".equals(table)) {
            return jdbc.queryForObject("""
                    SELECT COUNT(*) FROM mb_invoice_event ie
                    JOIN mb_usage_event e ON e.id = ie.usage_event_id
                    WHERE e.period_id = ?
                    """, Integer.class, periodId);
        }
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE period_id = ?",
                Integer.class, periodId);
    }

    private record Fixture(Long tenantId, Long subscriptionId, Long periodId,
                           Long priceVersionId, Long meterId, LocalDateTime occurredAt) {
    }

    @Autowired private com.meterbill.product.service.ModelCatalogService models;
    @Autowired private com.meterbill.metering.service.UsageEventService usage;
    @Autowired private com.meterbill.metering.service.UsageEventProcessor processor;

    @Test
    void twoModelsInputOutputRatesAreIsolatedAndUnregisteredPairsRejected() throws Exception {
        Fixture f=createFixture("PER_UNIT",0,1L,List.of());
        Long product=jdbc.queryForObject("SELECT product_id FROM mb_usage_meter WHERE id=?",Long.class,f.meterId());
        jdbc.update("INSERT INTO mb_user(email,password_hash,display_name) VALUES (?,'test','Catalog admin')",UUID.randomUUID()+"@example.test");
        Long actor=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        jdbc.update("INSERT INTO mb_platform_admin(user_id) VALUES (?)",actor);
        var meters=new java.util.ArrayList<Long>(); meters.add(f.meterId());
        for(int i=1;i<4;i++) {
            jdbc.update("INSERT INTO mb_usage_meter(product_id,meter_code,name,unit) VALUES (?,?,?,'TOKEN')",product,"MODEL_METER_"+i,"Model meter "+i);
            Long meter=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class); meters.add(meter);
            jdbc.update("INSERT INTO mb_usage_price_rule(price_version_id,meter_id,pricing_mode,included_quantity,unit_amount_minor) VALUES (?,?,'PER_UNIT',0,?)",f.priceVersionId(),meter,i+1);
        }
        models.create(actor,product,"model-a","Model A",meters.subList(0,2));
        models.create(actor,product,"model-b","Model B",meters.subList(2,4));
        assertEquals(2,models.list(actor,product).size());
        assertThrows(BusinessException.class,()->models.create(actor,product,"model-c","Rebind",List.of(meters.getFirst())));
        assertThrows(BusinessException.class,()->models.create(-1L,product,"denied","Denied",List.of(meters.getFirst())));
        for(int i=0;i<4;i++) {
            String code=i==0 ? "input_tokens" : "MODEL_METER_"+i;
            String model=i<2 ? "model-a" : "model-b";
            var request=new com.meterbill.metering.web.dto.UsageEventRequest(f.subscriptionId(),"models","event-"+i,code,model,10L,f.occurredAt().toInstant(ZoneOffset.UTC));
            var accepted=usage.accept(f.tenantId(),request); processor.process(accepted.id());
            assertEquals(accepted.id(),usage.accept(f.tenantId(),request).id());
        }
        assertThrows(BusinessException.class,()->usage.accept(f.tenantId(),new com.meterbill.metering.web.dto.UsageEventRequest(f.subscriptionId(),"models","invalid","input_tokens","model-b",1L,f.occurredAt().toInstant(ZoneOffset.UTC))));
        jdbc.update("INSERT INTO mb_usage_meter(product_id,meter_code,name,unit) VALUES (?,'NO_RATE','No rate','TOKEN')",product);
        Long unpriced=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        models.create(actor,product,"model-unpriced","Unpriced",List.of(unpriced));
        assertThrows(BusinessException.class,()->usage.accept(f.tenantId(),new com.meterbill.metering.web.dto.UsageEventRequest(f.subscriptionId(),"models","no-rate","NO_RATE","model-unpriced",1L,f.occurredAt().toInstant(ZoneOffset.UTC))));
        var invoice=invoices.issuePeriod(f.periodId());
        assertEquals(5100L,invoice.totalAmountMinor());
        assertEquals(5,invoice.lines().size());
        assertTrue(reconciliations.run(f.tenantId(),"models",null).differences().isEmpty());
    }
    private record Tier(Long upToQuantity, long unitAmountMinor) {
    }
    @Autowired private com.meterbill.payment.service.PaymentService payments;
    @Autowired private com.meterbill.operations.service.ReconciliationService reconciliations;
    @Autowired private com.meterbill.ledger.service.LedgerService ledger;

    @Test
    void unpaidInvoiceIsPostedImmediatelyAndHistoricalReplayRepairsOnlyMissingFacts() throws Exception {
        Fixture f = createFixture("PER_UNIT", 0, 1L, List.of());
        InvoiceVO invoice = invoices.issuePeriod(f.periodId());
        assertTrue(reconciliations.run(f.tenantId(), "first", null).differences().isEmpty());
        Long transaction = jdbc.queryForObject("SELECT id FROM mb_ledger_transaction WHERE tenant_id=?", Long.class, f.tenantId());
        // Isolated legacy fixture: this row did not exist in the old issue implementation.
        jdbc.update("DELETE FROM mb_ledger_entry WHERE transaction_id=?", transaction);
        jdbc.update("DELETE FROM mb_ledger_transaction WHERE id=?", transaction);
        invoices.issuePeriod(f.periodId());
        invoices.issuePeriod(f.periodId());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mb_ledger_transaction WHERE tenant_id=?", Integer.class, f.tenantId()));
        assertTrue(reconciliations.run(f.tenantId(), "repaired", null).differences().isEmpty());
        assertEquals(invoice.totalAmountMinor(), invoices.getInvoice(f.tenantId(), invoice.id()).totalAmountMinor());
    }

    @Test
    void lateAdjustmentAndPaymentCompeteUnderOneInvoiceLock() throws Exception {
        Fixture f = createFixture("PER_UNIT", 10, 3L, List.of());
        recordProcessedEvents(f, 20);
        InvoiceVO invoice = invoices.issuePeriod(f.periodId());
        var payment = payments.createPayment(f.tenantId(), invoice.id(), "pay", 5030L, null);
        recordProcessedEvents(f, 5);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var a = pool.submit(() -> { start.await(); return invoices.adjustLateUsage(f.periodId()); });
            var b = pool.submit(() -> { start.await(); return payments.simulatePaymentResult(payment.id(), null, "SUCCEEDED"); });
            start.countDown();
            assertEquals(15L, a.get(20, java.util.concurrent.TimeUnit.SECONDS).amountMinor());
            b.get(20, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertEquals("PARTIALLY_PAID", invoices.getInvoice(f.tenantId(), invoice.id()).status());
        assertTrue(reconciliations.run(f.tenantId(), "after", null).differences().isEmpty());
        assertTrue(ledger.isBalanced(f.tenantId()));
    }

    @Test
    void negativeAndZeroAdjustmentsPreserveHistoricalFactsAndReconcile() throws Exception {
        Fixture negative = createFixture("VOLUME", 0, null, List.of(new Tier(100L, 3), new Tier(null, 1)));
        recordProcessedEvents(negative, 100);
        InvoiceVO invoice = invoices.issuePeriod(negative.periodId());
        var payment = payments.createPayment(negative.tenantId(), invoice.id(), "pay", 5300L, null);
        payments.simulatePaymentResult(payment.id(), null, "SUCCEEDED");
        recordProcessedEvents(negative, 1);
        assertEquals(-199L, invoices.adjustLateUsage(negative.periodId()).amountMinor());
        assertEquals(5300L, invoices.getInvoice(negative.tenantId(), invoice.id()).totalAmountMinor());
        assertEquals("PAID", invoices.getInvoice(negative.tenantId(), invoice.id()).status());
        assertTrue(reconciliations.run(negative.tenantId(), "after", null).differences().isEmpty());
        assertTrue(ledger.isBalanced(negative.tenantId()));
        Fixture zero = createFixture("PER_UNIT", 100, 1L, List.of());
        recordProcessedEvents(zero, 10);
        invoices.issuePeriod(zero.periodId());
        recordProcessedEvents(zero, 5);
        assertEquals(0L, invoices.adjustLateUsage(zero.periodId()).amountMinor());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mb_ledger_transaction WHERE tenant_id=?", Integer.class, zero.tenantId()));
        assertTrue(reconciliations.run(zero.tenantId(), "zero", null).differences().isEmpty());
    }

    @Test
    void postingFailureRollsBackInvoiceAndAdjustmentAssignments() throws Exception {
        Fixture f = createFixture("PER_UNIT", 0, 1L, List.of());
        recordProcessedEvents(f, 10);
        jdbc.update("INSERT INTO mb_ledger_account(tenant_id,account_code,account_type,currency,status) VALUES (?,'AR','ASSET','CNY','CLOSED')", f.tenantId());
        assertThrows(BusinessException.class, () -> invoices.issuePeriod(f.periodId()));
        assertEquals(0, countForPeriod("mb_invoice", f.periodId()));
        assertEquals(0, countForPeriod("mb_invoice_event", f.periodId()));
        jdbc.update("UPDATE mb_ledger_account SET status='ACTIVE' WHERE tenant_id=?", f.tenantId());
        invoices.issuePeriod(f.periodId());
        recordProcessedEvents(f, 5);
        jdbc.update("UPDATE mb_ledger_account SET status='CLOSED' WHERE tenant_id=? AND account_code='AR'", f.tenantId());
        assertThrows(BusinessException.class, () -> invoices.adjustLateUsage(f.periodId()));
        assertEquals(0, countForPeriod("mb_invoice_adjustment", f.periodId()));
        assertEquals(1, countForPeriod("mb_invoice_event", f.periodId()));
    }
}

