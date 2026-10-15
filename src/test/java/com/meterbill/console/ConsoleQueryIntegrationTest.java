package com.meterbill.console;

import com.meterbill.console.service.ConsoleQueryService;
import com.meterbill.common.exception.BusinessException;
import com.meterbill.product.security.PlatAdminGuard;
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

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class ConsoleQueryIntegrationTest {

    @Container
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4")
            .withDatabaseName("meterbill_console_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ConsoleQueryService queries;

    @Autowired
    PlatAdminGuard platformAdmins;

    @Test
    void returnsOnlyPersistedRowsForTheRequestedTenant() {
        Fixture first = fixture("first");
        Fixture second = fixture("second");

        assertEquals(1, queries.subscriptions(first.tenantId(), 1, 20, null).total());
        assertEquals(first.subscriptionId(), queries.subscriptions(first.tenantId(), 1, 20, null)
                .records().getFirst().id());
        assertEquals(1, queries.usageEvents(first.tenantId(), 1, 20, null, null).total());
        assertEquals("event-first", queries.usageEvents(first.tenantId(), 1, 20, null, null)
                .records().getFirst().eventId());
        assertEquals(1, queries.invoices(first.tenantId(), 1, 20, null, null).total());
        assertEquals(1, queries.payments(first.tenantId(), 1, 20, "UNKNOWN", first.invoiceId()).total());
        assertEquals(1, queries.tasks(first.tenantId(), 1, 20, null).total());
        assertEquals(1, queries.members(first.tenantId()).size());
        assertEquals(1, queries.differences(first.tenantId(), "OPEN", 20).size());
        assertEquals(1, queries.paymentInvestigation(first.tenantId(), first.paymentId()).differences().size());
        assertTrue(queries.paymentInvestigation(first.tenantId(), first.paymentId()).refunds().isEmpty());

        assertEquals(second.subscriptionId(), queries.subscriptions(second.tenantId(), 1, 20, null)
                .records().getFirst().id());
        assertTrue(queries.paymentInvestigation(first.tenantId(), first.paymentId()).timeline().size() >= 2);
        assertEquals(2_500, queries.dashboard(first.tenantId()).receivableMinor());
        assertEquals(0, queries.dashboard(first.tenantId()).paidMinor());
        assertEquals(1, queries.dashboard(first.tenantId()).unknownPayments());
    }

    @Test
    void pagesAndFiltersPersistedCustomersAndProtectsPlatformAccess() {
        Fixture first = fixture("customer-first");
        fixture("customer-second");

        var page = queries.customers(1, 1, "ACTIVE", "customer-first");
        assertEquals(1, page.total());
        assertEquals(first.tenantId(), page.records().getFirst().tenantId());
        assertEquals(1, page.records().getFirst().memberCount());
        assertEquals(1, page.records().getFirst().activeSubscriptionCount());
        assertEquals(1, page.records().getFirst().invoiceCount());
        assertEquals(2_500, page.records().getFirst().outstandingMinor());

        Long ordinaryUser = jdbc.queryForObject(
                "SELECT user_id FROM mb_tenant_member WHERE tenant_id = ? LIMIT 1",
                Long.class, first.tenantId());
        assertThrows(BusinessException.class, () -> platformAdmins.requireAdmin(ordinaryUser));
        jdbc.update("INSERT INTO mb_platform_admin(user_id) VALUES (?)", ordinaryUser);
        platformAdmins.requireAdmin(ordinaryUser);
    }

    private Fixture fixture(String name) {
        String suffix = name + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        jdbc.update("INSERT INTO mb_tenant(tenant_code,name) VALUES (?,?)", suffix, "Tenant " + name);
        Long tenant = lastId();
        jdbc.update("INSERT INTO mb_user(email,password_hash,display_name) VALUES (?,?,?)",
                suffix + "@example.test", "hash", "User " + name);
        Long user = lastId();
        jdbc.update("INSERT INTO mb_tenant_member(tenant_id,user_id) VALUES (?,?)", tenant, user);

        jdbc.update("INSERT INTO mb_product(product_code,name,status) VALUES (?,?,'ACTIVE')", "product-" + suffix, "Product");
        Long product = lastId();
        jdbc.update("INSERT INTO mb_plan(product_id,plan_code,name,status) VALUES (?,?,?,'ACTIVE')", product, "plan-" + suffix, "Plan");
        Long plan = lastId();
        jdbc.update("INSERT INTO mb_price(plan_id,currency,billing_period) VALUES (?,'CNY','MONTHLY')", plan);
        Long price = lastId();
        jdbc.update("INSERT INTO mb_price_version(price_id,version_no,base_amount_minor,status,effective_at) VALUES (?,1,2500,'PUBLISHED',?)",
                price, LocalDateTime.now().minusMonths(2));
        Long version = lastId();
        jdbc.update("INSERT INTO mb_usage_meter(product_id,meter_code,name,unit) VALUES (?,?,?,'request')", product, "meter-" + suffix, "Requests");
        Long meter = lastId();
        jdbc.update("INSERT INTO mb_subscription(tenant_id,plan_id,idempotency_key,request_hash,status) VALUES (?,?,?,?,'ACTIVE')",
                tenant, plan, "sub-" + suffix, "0".repeat(64));
        Long subscription = lastId();
        LocalDateTime start = LocalDateTime.now().minusDays(5);
        LocalDateTime end = LocalDateTime.now().plusDays(25);
        jdbc.update("""
                INSERT INTO mb_subscription_period(tenant_id,subscription_id,plan_id,price_id,price_version_id,
                    currency,base_amount_minor,active_from,period_start,period_end,status)
                VALUES (?,?,?,?,?,'CNY',2500,?,?,?,'ACTIVE')
                """, tenant, subscription, plan, price, version, start, start, end);
        Long period = lastId();
        jdbc.update("""
                INSERT INTO mb_usage_event(tenant_id,subscription_id,period_id,meter_id,source_id,event_id,
                    model_code,quantity,occurred_at,payload_hash,status,delivery_count)
                VALUES (?,?,?,?,'console',?,'model',7,?,?, 'RETRYABLE_FAILED',2)
                """, tenant, subscription, period, meter, "event-" + name,
                LocalDateTime.now(), "1".repeat(64));
        Long event = lastId();
        jdbc.update("INSERT INTO mb_outbox(event_type,event_id,status,attempt_count,last_error) VALUES ('USAGE_EVENT',?,'RETRYABLE_FAILED',2,'timeout')", event);
        jdbc.update("""
                INSERT INTO mb_invoice(tenant_id,subscription_id,period_id,price_version_id,currency,
                    period_start,period_end,base_amount_minor,usage_amount_minor,total_amount_minor,status)
                VALUES (?,?,?,?,'CNY',?,?,2500,0,2500,'ISSUED')
                """, tenant, subscription, period, version, start, end);
        Long invoice = lastId();
        jdbc.update("""
                INSERT INTO mb_payment(tenant_id,invoice_id,idempotency_key,request_hash,external_reference,currency,amount_minor,status)
                VALUES (?,?,?,? ,?,'CNY',2500,'UNKNOWN')
                """, tenant, invoice, "pay-" + suffix, "2".repeat(64), "external-" + suffix);
        Long payment = lastId();
        jdbc.update("""
                INSERT INTO mb_channel_transaction(tenant_id,payment_id,channel_code,external_reference,
                    transaction_type,currency,amount_minor,status)
                VALUES (?,?,'SIMULATED',?,'CHARGE','CNY',2500,'UNKNOWN')
                """, tenant, payment, "external-" + suffix);
        jdbc.update("""
                INSERT INTO mb_reconciliation_difference(tenant_id,fingerprint,difference_type,reference_type,
                    reference_id,currency,expected_amount_minor,actual_amount_minor,status,evidence)
                VALUES (?,?, 'PAYMENT_STATUS','PAYMENT',?,'CNY',2500,0,'OPEN','{}')
                """, tenant, "fp-" + suffix, payment.toString());
        return new Fixture(tenant, subscription, invoice, payment);
    }

    private Long lastId() {
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private record Fixture(Long tenantId, Long subscriptionId, Long invoiceId, Long paymentId) {
    }
}
