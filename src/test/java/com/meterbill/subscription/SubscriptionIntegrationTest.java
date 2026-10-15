package com.meterbill.subscription;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SubscriptionIntegrationTest {

    @Container
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4")
            .withDatabaseName("meterbill_subscription_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder encoder;
    @Autowired private com.meterbill.identity.bootstrap.TenantRoleSetup roleSetup;

    private static final String PASSWORD = "TestPassword-2026!";

    private String email;
    private Long tenantId;
    private Long planId;
    private Long secondPlanId;

    @BeforeEach
    void prepareData() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        email = "subscription-" + suffix + "@example.test";
        jdbc.update("""
                INSERT INTO mb_user(email, password_hash, display_name)
                VALUES (?, ?, ?)
                """, email, encoder.encode(PASSWORD), email);
        Long userId = jdbc.queryForObject("SELECT id FROM mb_user WHERE email = ?", Long.class, email);

        jdbc.update("INSERT INTO mb_tenant(tenant_code, name) VALUES (?, ?)",
                "tenant-" + suffix, "Subscription Test Tenant");
        tenantId = jdbc.queryForObject("SELECT id FROM mb_tenant WHERE tenant_code = ?",
                Long.class, "tenant-" + suffix);
        jdbc.update("INSERT INTO mb_tenant_member(tenant_id, user_id) VALUES (?, ?)", tenantId, userId);
        Long memberId = jdbc.queryForObject("SELECT id FROM mb_tenant_member WHERE tenant_id=? AND user_id=?",
                Long.class, tenantId, userId);
        roleSetup.ensureDefaults(tenantId);
        roleSetup.grantLocalOwner(tenantId, memberId);

        Long productId = insertProduct("PRODUCT_" + suffix);
        planId = insertPlan(productId, "PLAN_" + suffix, true);
        secondPlanId = insertPlan(productId, "ALT_" + suffix, true);
    }

    @Test
    void idempotencyPreventsDuplicatesAndRejectsDifferentPayload() throws Exception {
        String token = login();
        Long subscriptionId = createSubscription(token, planId, "create-1");

        String retryJson = mvc.perform(post("/api/v1/tenant/subscriptions")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-Id", tenantId)
                        .header("Idempotency-Key", "create-1")
                        .contentType("application/json")
                        .content("{\"planId\":%d}".formatted(planId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Number retryId = JsonPath.read(retryJson, "$.data.id");
        assertEquals(subscriptionId, retryId.longValue());

        mvc.perform(post("/api/v1/tenant/subscriptions")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-Id", tenantId)
                        .header("Idempotency-Key", "create-1")
                        .contentType("application/json")
                        .content("{\"planId\":%d}".formatted(secondPlanId)))
                .andExpect(status().isConflict());

        assertEquals(1L, jdbc.queryForObject("""
                SELECT COUNT(*) FROM mb_subscription
                WHERE tenant_id = ? AND idempotency_key = 'create-1'
                """, Long.class, tenantId));
    }

    @Test
    void activationUsesTenantNaturalMonthAndOtherTenantCannotRead() throws Exception {
        String token = login();
        Long subscriptionId = createSubscription(token, planId, "create-2");

        mvc.perform(post("/api/v1/tenant/subscriptions/{id}/activate", subscriptionId)
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-Id", tenantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.priceVersionId").isNumber())
                .andExpect(jsonPath("$.data.periodId").isNumber());

        LocalDateTime start = jdbc.queryForObject("""
                SELECT period_start FROM mb_subscription_period
                WHERE subscription_id = ? AND status = 'ACTIVE'
                """, LocalDateTime.class, subscriptionId);
        LocalDateTime end = jdbc.queryForObject("""
                SELECT period_end FROM mb_subscription_period
                WHERE subscription_id = ? AND status = 'ACTIVE'
                """, LocalDateTime.class, subscriptionId);
        Instant activeFrom = jdbc.queryForObject("""
                SELECT active_from FROM mb_subscription_period WHERE subscription_id = ?
                """, (rs, n) -> rs.getObject(1, LocalDateTime.class).toInstant(ZoneOffset.UTC),
                subscriptionId);
        ZoneId tenantZone = ZoneId.of("Asia/Shanghai");
        assertEquals(1, start.toInstant(ZoneOffset.UTC).atZone(tenantZone).getDayOfMonth());
        assertEquals(1, end.toInstant(ZoneOffset.UTC).atZone(tenantZone).getDayOfMonth());
        assertTrue(!activeFrom.isBefore(start.toInstant(ZoneOffset.UTC))
                && activeFrom.isBefore(end.toInstant(ZoneOffset.UTC)));

        Long otherUserId = jdbc.queryForObject("""
                SELECT id FROM mb_user WHERE email = ?
                """, Long.class, email);
        jdbc.update("INSERT INTO mb_tenant(tenant_code, name) VALUES (?, ?)",
                "other-" + UUID.randomUUID(), "Other Tenant");
        Long otherTenantId = jdbc.queryForObject("SELECT MAX(id) FROM mb_tenant", Long.class);
        jdbc.update("INSERT INTO mb_tenant_member(tenant_id, user_id) VALUES (?, ?)",
                otherTenantId, otherUserId);

        mvc.perform(get("/api/v1/tenant/subscriptions/{id}", subscriptionId)
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-Id", otherTenantId))
                .andExpect(status().isNotFound());
    }

    @Test
    void cancelAtPeriodEndExpiresOnRenewAndImmediateCancelIsTerminal() throws Exception {
        String token = login();
        Long scheduled = createSubscription(token, planId, "create-3");
        activate(token, scheduled);

        mvc.perform(post("/api/v1/tenant/subscriptions/{id}/cancel", scheduled)
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-Id", tenantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCEL_SCHEDULED"));
        jdbc.update("""
                UPDATE mb_subscription_period
                SET period_start = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 MONTH),
                    period_end = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 SECOND)
                WHERE subscription_id = ? AND status = 'ACTIVE'
                """, scheduled);

        mvc.perform(post("/api/v1/tenant/subscriptions/{id}/renew", scheduled)
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-Id", tenantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("EXPIRED"));

        Long pending = createSubscription(token, planId, "create-4");
        mvc.perform(post("/api/v1/tenant/subscriptions/{id}/cancel", pending)
                        .param("immediate", "true")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-Id", tenantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELED"));

        Long active = createSubscription(token, planId, "create-4-active");
        activate(token, active);
        LocalDateTime naturalPeriodEnd = jdbc.queryForObject("""
                SELECT period_end FROM mb_subscription_period
                WHERE subscription_id = ? AND status = 'ACTIVE'
                """, LocalDateTime.class, active);

        mvc.perform(post("/api/v1/tenant/subscriptions/{id}/cancel", active)
                        .param("immediate", "true")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-Id", tenantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELED"))
                .andExpect(jsonPath("$.data.periodId").isNumber());

        LocalDateTime actualPeriodEnd = jdbc.queryForObject("""
                SELECT period_end FROM mb_subscription_period
                WHERE subscription_id = ? AND status = 'ENDED'
                """, LocalDateTime.class, active);
        assertTrue(actualPeriodEnd.isBefore(naturalPeriodEnd));
        assertTrue(!actualPeriodEnd.isAfter(LocalDateTime.now(ZoneOffset.UTC).plusSeconds(1)));
    }

    @Test
    void scheduledPlanChangeTakesEffectOnRenewal() throws Exception {
        String token = login();
        Long subscriptionId = createSubscription(token, planId, "create-5");
        activate(token, subscriptionId);

        mvc.perform(post("/api/v1/tenant/subscriptions/{id}/plan-changes", subscriptionId)
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-Id", tenantId)
                        .header("Idempotency-Key", "change-1")
                        .contentType("application/json")
                        .content("{\"planId\":%d}".formatted(secondPlanId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.planId").value(planId));
        jdbc.update("""
                UPDATE mb_subscription_period
                SET period_start = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 MONTH),
                    period_end = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 DAY)
                WHERE subscription_id = ? AND status = 'ACTIVE'
                """, subscriptionId);
        jdbc.update("""
                UPDATE mb_subscription_change c
                JOIN mb_subscription_period p ON p.subscription_id = c.subscription_id
                SET c.effective_at = p.period_end
                WHERE c.subscription_id = ? AND c.status = 'SCHEDULED'
                """, subscriptionId);

        mvc.perform(post("/api/v1/tenant/subscriptions/{id}/renew", subscriptionId)
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-Id", tenantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.planId").value(secondPlanId));

        assertEquals("APPLIED", jdbc.queryForObject("""
                SELECT status FROM mb_subscription_change
                WHERE subscription_id = ?
                """, String.class, subscriptionId));
    }

    @Test
    void subscriptionWithUnavailableMonthlyPriceCannotActivate() throws Exception {
        String token = login();
        Long unavailable = insertPlan(
                insertProduct("EMPTY_" + UUID.randomUUID().toString().substring(0, 8)),
                "EMPTY", false);
        Long subscriptionId = createSubscription(token, unavailable, "create-6");

        mvc.perform(post("/api/v1/tenant/subscriptions/{id}/activate", subscriptionId)
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-Id", tenantId))
                .andExpect(status().isConflict());
        assertEquals("PENDING", jdbc.queryForObject("""
                SELECT status FROM mb_subscription WHERE id = ?
                """, String.class, subscriptionId));
    }

    private Long createSubscription(String token, Long selectedPlan, String key) throws Exception {
        String json = mvc.perform(post("/api/v1/tenant/subscriptions")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-Id", tenantId)
                        .header("Idempotency-Key", key)
                        .contentType("application/json")
                        .content("{\"planId\":%d}".formatted(selectedPlan)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Number id = JsonPath.read(json, "$.data.id");
        return id.longValue();
    }

    private void activate(String token, Long subscriptionId) throws Exception {
        mvc.perform(post("/api/v1/tenant/subscriptions/{id}/activate", subscriptionId)
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-Id", tenantId))
                .andExpect(status().isOk());
    }

    private Long insertProduct(String code) {
        jdbc.update("""
                INSERT INTO mb_product(product_code, name, status)
                VALUES (?, ?, 'ACTIVE')
                """, code, code);
        return jdbc.queryForObject("SELECT id FROM mb_product WHERE product_code = ?",
                Long.class, code);
    }

    private Long insertPlan(Long productId, String code, boolean withPrice) {
        jdbc.update("""
                INSERT INTO mb_plan(product_id, plan_code, name, status)
                VALUES (?, ?, ?, 'ACTIVE')
                """, productId, code, code);
        Long id = jdbc.queryForObject("""
                SELECT id FROM mb_plan WHERE product_id = ? AND plan_code = ?
                """, Long.class, productId, code);
        if (withPrice) {
            jdbc.update("""
                    INSERT INTO mb_price(plan_id, currency, billing_period)
                    VALUES (?, 'CNY', 'MONTHLY')
                    """, id);
            Long priceId = jdbc.queryForObject(
                    "SELECT id FROM mb_price WHERE plan_id = ?", Long.class, id);
            jdbc.update("""
                    INSERT INTO mb_price_version
                        (price_id, version_no, base_amount_minor, status, effective_at)
                    VALUES (?, 1, 19900, 'PUBLISHED',
                            DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 7 DAY))
                    """, priceId);
        }
        return id;
    }

    private String login() throws Exception {
        String json = mvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(email, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.data.accessToken");
    }
}
