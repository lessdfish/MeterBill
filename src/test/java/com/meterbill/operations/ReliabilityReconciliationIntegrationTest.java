package com.meterbill.operations;

import com.meterbill.billing.service.InvoiceService;
import com.meterbill.billing.web.vo.InvoiceVO;
import com.meterbill.common.exception.BusinessException;
import com.meterbill.operations.service.InvoiceRecoveryJob;
import com.meterbill.operations.service.ReconciliationService;
import com.meterbill.operations.service.TaskCoordinationService;
import com.meterbill.operations.service.UsageEventRecoveryService;
import com.meterbill.operations.web.vo.ReconciliationDifferenceVO;
import com.meterbill.operations.web.vo.ReconciliationRunVO;
import com.meterbill.payment.service.PaymentService;
import com.meterbill.metering.service.UsageEventProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class ReliabilityReconciliationIntegrationTest {

    @Container
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4")
            .withDatabaseName("meterbill_reliability_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7.4.11-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("meterbill.redisson.enabled", () -> true);
        registry.add("meterbill.redisson.host", redis::getHost);
        registry.add("meterbill.redisson.port", () -> redis.getMappedPort(6379));
        registry.add("meterbill.redisson.password", () -> "");
        registry.add("meterbill.jobs.enabled", () -> true);
        registry.add("meterbill.jobs.invoice-recovery-interval", () -> 3_600_000L);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private InvoiceService invoices;

    @Autowired
    private PaymentService payments;

    @Autowired
    private ReconciliationService reconciliations;

    @Autowired
    private TaskCoordinationService coordination;

    @Autowired
    private InvoiceRecoveryJob recoveryJob;

    @Autowired
    private UsageEventRecoveryService usageEventRecovery;

    @Autowired
    private UsageEventProcessor usageEventProcessor;

    @Test
    void reconciliationFindsMismatchesIsIdempotentAndKeepsResolutionHistory() throws Exception {
        Fixture fixture = createInvoice();
        var payment = payments.createPayment(fixture.tenantId(), fixture.invoiceId(),
                "recon-pay-" + fixture.suffix(), 1_000L, null);
        payments.simulatePaymentResult(payment.id(), null, "SUCCEEDED");
        jdbc.update("UPDATE mb_channel_transaction SET amount_minor = 900 WHERE payment_id = ?",
                payment.id());
        jdbc.update("UPDATE mb_invoice SET status = 'ISSUED' WHERE id = ?", fixture.invoiceId());

        ReconciliationRunVO run = reconciliations.run(fixture.tenantId(),
                "recon-run-" + fixture.suffix(), null);
        ReconciliationRunVO replay = reconciliations.run(fixture.tenantId(),
                "recon-run-" + fixture.suffix(), null);

        assertEquals(run.id(), replay.id());
        assertEquals("COMPLETED", run.status());
        assertEquals(3, run.differences().size());
        assertTrue(run.differences().stream().anyMatch(d -> "CHANNEL_TRANSACTION_MISMATCH".equals(d.differenceType())));
        ReconciliationDifferenceVO channel = run.differences().stream()
                .filter(difference -> "CHANNEL_CASH_MISMATCH".equals(difference.differenceType()))
                .findFirst().orElseThrow();
        assertEquals(900L, channel.expectedAmountMinor());
        assertEquals(1_000L, channel.actualAmountMinor());
        ReconciliationDifferenceVO invoiceStatus = run.differences().stream()
                .filter(difference -> "INVOICE_PAYMENT_STATUS_MISMATCH".equals(difference.differenceType()))
                .findFirst().orElseThrow();
        assertEquals("PAID", invoiceStatus.expectedStatus());
        assertEquals("ISSUED", invoiceStatus.actualStatus());
        assertTrue(reconciliations.listDifferences("OPEN", 100).stream()
                .anyMatch(difference -> difference.id().equals(channel.id())
                        && difference.tenantId().equals(fixture.tenantId())));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM mb_reconciliation_run
                WHERE tenant_id = ? AND idempotency_key = ?
                """, Integer.class, fixture.tenantId(), "recon-run-" + fixture.suffix()));

        assertEquals("INVESTIGATING", reconciliations.updateDifferenceStatus(
                channel.id(), "INVESTIGATING", "已核对渠道流水", 101L).status());
        ReconciliationDifferenceVO resolved = reconciliations.updateDifferenceStatus(
                channel.id(), "RESOLVED", "已补录并完成复核", 101L);
        assertEquals("RESOLVED", resolved.status());
        assertNotNull(resolved.resolvedAt());
        assertEquals(2, jdbc.queryForObject("""
                SELECT COUNT(*) FROM mb_reconciliation_difference_action WHERE difference_id = ?
                """, Integer.class, channel.id()));

        ReconciliationRunVO nextRun = reconciliations.run(fixture.tenantId(),
                "recon-rerun-" + fixture.suffix(), null);
        ReconciliationDifferenceVO reopened = nextRun.differences().stream()
                .filter(difference -> difference.id().equals(channel.id())).findFirst().orElseThrow();
        assertEquals("OPEN", reopened.status());
    }

    @Test
    void redissonExcludesConcurrentOwnerAndRecoveryIssuesDuePeriodOnce() throws Exception {
        String lockKey = "integration-test:" + UUID.randomUUID();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> first = worker.submit(() -> coordination.runIfAcquired(lockKey, () -> {
                entered.countDown();
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
            }));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            assertFalse(coordination.runIfAcquired(lockKey, () -> {
                throw new AssertionError("A second owner entered the distributed lock");
            }));
            release.countDown();
            assertTrue(first.get(10, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            worker.shutdownNow();
        }

        Fixture fixture = createDuePeriod();
        assertEquals(0, invoiceCount(fixture.periodId()));
        recoveryJob.recoverDueInvoices();
        recoveryJob.recoverDueInvoices();
        assertEquals(1, invoiceCount(fixture.periodId()));
        assertEquals(0L, invoices.findDueUnbilledPeriodIds(500).stream()
                .filter(fixture.periodId()::equals).count());
    }

    @Test
    void finalFailedUsageEventCanBeRetriedOnceWithoutDuplicateAggregation() throws Exception {
        Fixture fixture = createDuePeriod();
        jdbc.update("""
                INSERT INTO mb_usage_event
                    (tenant_id, subscription_id, period_id, meter_id, source_id, event_id,
                     model_code, quantity, occurred_at, payload_hash, status, delivery_count, last_error)
                VALUES (?, ?, ?, ?, 'recovery-test', ?, 'model-test', 17, ?, ?, 'FINAL_FAILED', 3,
                        'temporary downstream failure')
                """, fixture.tenantId(), fixture.subscriptionId(), fixture.periodId(),
                fixture.meterId(), "failed-" + fixture.suffix(),
                LocalDateTime.now(ZoneOffset.UTC).minusSeconds(5), "a".repeat(64));
        Long eventId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO mb_outbox (event_type, event_id, status, attempt_count, last_error)
                VALUES ('USAGE_EVENT_ACCEPTED', ?, 'FINAL_FAILED', 8, 'publish retry limit reached')
                """, eventId);

        var retry = usageEventRecovery.retryFinalFailure(eventId, 101L);
        assertEquals("RETRYABLE_FAILED", retry.eventStatus());
        assertEquals("READY", retry.outboxStatus());
        assertThrows(BusinessException.class,
                () -> usageEventRecovery.retryFinalFailure(eventId, 101L));
        assertEquals(0, jdbc.queryForObject("""
                SELECT attempt_count FROM mb_outbox WHERE event_id = ?
                """, Integer.class, eventId));

        usageEventProcessor.process(eventId);
        usageEventProcessor.process(eventId);
        assertEquals(17L, jdbc.queryForObject("""
                SELECT total_quantity FROM mb_usage_period_total WHERE period_id = ?
                """, Long.class, fixture.periodId()));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM mb_usage_event_processed WHERE usage_event_id = ?
                """, Integer.class, eventId));
    }

    private Fixture createInvoice() throws Exception {
        return createFixture(true);
    }

    private Fixture createDuePeriod() throws Exception {
        return createFixture(false);
    }

    private Fixture createFixture(boolean issue) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO mb_tenant(tenant_code, name) VALUES (?, ?)",
                "reliable-" + suffix, "Reliability Test");
        Long tenantId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO mb_product(product_code, name, status) VALUES (?, ?, 'ACTIVE')",
                "reliable-product-" + suffix, "Reliability Product");
        Long productId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO mb_usage_meter(product_id, meter_code, name, unit)
                VALUES (?, 'input_tokens', 'Input Tokens', 'token')
                """, productId);
        Long meterId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO mb_plan(product_id, plan_code, name, status)
                VALUES (?, ?, 'Reliability Plan', 'ACTIVE')
                """, productId, "reliable-plan-" + suffix);
        Long planId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO mb_price(plan_id, currency, billing_period) VALUES (?, 'CNY', 'MONTHLY')",
                planId);
        Long priceId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO mb_price_version
                    (price_id, version_no, base_amount_minor, status, effective_at)
                VALUES (?, 1, 1000, 'PUBLISHED', ?)
                """, priceId, LocalDateTime.ofInstant(
                Instant.now().minusSeconds(120L * 86400), ZoneOffset.UTC));
        Long versionId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO mb_subscription(tenant_id, plan_id, idempotency_key, request_hash, status)
                VALUES (?, ?, ?, ?, 'ACTIVE')
                """, tenantId, planId, "reliable-" + suffix, "0".repeat(64));
        Long subscriptionId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        LocalDateTime start = LocalDateTime.ofInstant(
                Instant.now().minusSeconds(90L * 86400), ZoneOffset.UTC);
        LocalDateTime end = LocalDateTime.ofInstant(
                Instant.now().minusSeconds(60L * 86400), ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO mb_subscription_period
                    (tenant_id, subscription_id, plan_id, price_id, price_version_id,
                     currency, base_amount_minor, active_from, period_start, period_end, status)
                VALUES (?, ?, ?, ?, ?, 'CNY', 1000, ?, ?, ?, 'ENDED')
                """, tenantId, subscriptionId, planId, priceId, versionId, start, start, end);
        Long periodId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        Long invoiceId = issue ? invoices.issuePeriod(periodId).id() : null;
        return new Fixture(tenantId, periodId, invoiceId, subscriptionId, meterId, suffix);
    }

    private int invoiceCount(Long periodId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM mb_invoice WHERE period_id = ?",
                Integer.class, periodId);
    }

    private record Fixture(Long tenantId, Long periodId, Long invoiceId,
                           Long subscriptionId, Long meterId, String suffix) {
    }
    @Test
    void watchdogOwnerLosesLeaseAndStaleWorkStillCannotDuplicateInvoice() throws Exception {
        Fixture f = createDuePeriod();
        var config = new org.redisson.config.Config();
        config.setLockWatchdogTimeout(3000);
        config.useSingleServer().setAddress("redis://" + redis.getHost() + ":" + redis.getMappedPort(6379))
                .setConnectionMinimumIdleSize(1).setConnectionPoolSize(2).setSubscriptionConnectionMinimumIdleSize(1).setSubscriptionConnectionPoolSize(2);
        var firstClient = org.redisson.Redisson.create(config);
        var secondClient = org.redisson.Redisson.create(config);
        String task = "invoice-period:" + f.periodId();
        String key = "meterbill:task:" + task;
        var entered = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var oldOwner = pool.submit(() -> new TaskCoordinationService(firstClient).runIfAcquired(task, () -> {
                entered.countDown();
                try { assertTrue(resume.await(15, TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new RuntimeException(e); }
                invoices.issuePeriod(f.periodId());
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            // Verify watchdog renewal first, then inject loss of the owner's server-side lease.
            Thread.sleep(3500);
            assertTrue(secondClient.getLock(key).isLocked());
            assertEquals(1L, Long.parseLong(redis.execInContainer("redis-cli", "PEXPIRE", key, "1").getStdout().trim()));
            for (int i=0; i<100 && secondClient.getLock(key).isLocked(); i++) Thread.sleep(10);
            assertFalse(secondClient.getLock(key).isLocked());
            assertTrue(new TaskCoordinationService(secondClient).runIfAcquired(task, () -> {
                resume.countDown();
                invoices.issuePeriod(f.periodId());
            }));
            assertTrue(oldOwner.get(10, TimeUnit.SECONDS));
            assertEquals(1, invoiceCount(f.periodId()));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mb_ledger_transaction WHERE tenant_id=? AND business_type='INVOICE'",
                    Integer.class, f.tenantId()));
            assertTrue(reconciliations.run(f.tenantId(), "lost-lease", null).differences().isEmpty());
        } finally { resume.countDown(); firstClient.shutdown(); secondClient.shutdown(); }
    }
}

