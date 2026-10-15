package com.meterbill.metering;

import com.meterbill.common.exception.BusinessException;
import com.meterbill.metering.service.UsageEventService;
import com.meterbill.metering.service.UsageMeterCache;
import com.meterbill.metering.web.dto.UsageEventRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
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
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class UsageMeterCacheIntegrationTest {

    @Container
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4")
            .withDatabaseName("meterbill_cache_test")
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
        registry.add("meterbill.redis-cache.enabled", () -> true);
        registry.add("meterbill.redis-cache.usage-meter-ttl", () -> "2m");
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private UsageMeterCache meterCache;

    @Autowired
    private UsageEventService usageEvents;

    @Test
    void meterLookupCachesExpiresCanBeInvalidatedAndFallsBackWhenRedisStops() throws Exception {
        Fixture fixture = createFixture();
        Instant occurredAt = Instant.now().minusSeconds(2);
        UsageEventRequest firstRequest = request(fixture.subscriptionId(), "cache-first", occurredAt);

        var accepted = usageEvents.accept(fixture.tenantId(), firstRequest);
        assertEquals("RECEIVED", accepted.status());
        assertEquals("prompt_tokens", accepted.meterCode());

        Set<String> keys = redisTemplate.keys("meterbill:usage-meter:" + fixture.productId() + ":*");
        assertNotNull(keys);
        assertEquals(1, keys.size());
        String key = keys.iterator().next();
        assertEquals(fixture.meterId().toString(), redisTemplate.opsForValue().get(key));
        assertTrue(redisTemplate.getExpire(key) > 0);

        assertThrows(BusinessException.class,
                () -> usageEvents.accept(fixture.otherTenantId(), firstRequest));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM mb_usage_event
                WHERE tenant_id = ? AND event_id = ?
                """, Integer.class, fixture.tenantId(), "cache-first"));
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM mb_usage_event
                WHERE tenant_id = ? AND event_id = ?
                """, Integer.class, fixture.otherTenantId(), "cache-first"));

        meterCache.invalidate(fixture.productId(), "prompt_tokens", "model-cache-test");
        assertEquals(null, redisTemplate.opsForValue().get(key));
        var repopulated = usageEvents.accept(fixture.tenantId(),
                request(fixture.subscriptionId(), "cache-second", occurredAt));
        assertEquals("RECEIVED", repopulated.status());
        assertEquals(fixture.meterId().toString(), redisTemplate.opsForValue().get(key));

        assertThrows(BusinessException.class, () -> usageEvents.accept(fixture.tenantId(),
                new UsageEventRequest(fixture.subscriptionId(), "cache-source", "wrong-model",
                        "prompt_tokens", "unregistered-model", 5L, occurredAt)));
        redis.stop();
        var fallback = usageEvents.accept(fixture.tenantId(),
                request(fixture.subscriptionId(), "redis-down", occurredAt));
        assertEquals("RECEIVED", fallback.status());
        assertEquals(3, jdbc.queryForObject("""
                SELECT COUNT(*) FROM mb_usage_event WHERE tenant_id = ?
                """, Integer.class, fixture.tenantId()));
    }

    private Fixture createFixture() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO mb_tenant(tenant_code, name) VALUES (?, ?)",
                "cache-" + suffix, "Cache Test");
        Long tenantId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO mb_tenant(tenant_code, name) VALUES (?, ?)",
                "cache-other-" + suffix, "Cache Other Tenant");
        Long otherTenantId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO mb_product(product_code, name, status) VALUES (?, ?, 'ACTIVE')",
                "cache-product-" + suffix, "Cache Product");
        Long productId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO mb_usage_meter(product_id, meter_code, name, unit)
                VALUES (?, 'prompt_tokens', 'Prompt Tokens', 'token')
                """, productId);
        Long meterId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO mb_ai_model(product_id,model_code,name) VALUES (?,?,'Test model')", productId, "model-cache-test");
        Long modelId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO mb_model_meter(model_id,meter_id) VALUES (?,?)", modelId, meterId);
        jdbc.update("""
                INSERT INTO mb_plan(product_id, plan_code, name, status)
                VALUES (?, ?, 'Cache Plan', 'ACTIVE')
                """, productId, "cache-plan-" + suffix);
        Long planId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO mb_price(plan_id, currency, billing_period) VALUES (?, 'CNY', 'MONTHLY')",
                planId);
        Long priceId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO mb_price_version
                    (price_id, version_no, base_amount_minor, status, effective_at)
                VALUES (?, 1, 100, 'PUBLISHED', ?)
                """, priceId, LocalDateTime.ofInstant(Instant.now().minusSeconds(86400), ZoneOffset.UTC));
        Long versionId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO mb_usage_price_rule(price_version_id,meter_id,pricing_mode,included_quantity,unit_amount_minor) VALUES (?,?,'PER_UNIT',0,1)", versionId, meterId);
        jdbc.update("""
                INSERT INTO mb_subscription(tenant_id, plan_id, idempotency_key, request_hash, status)
                VALUES (?, ?, ?, ?, 'ACTIVE')
                """, tenantId, planId, "cache-sub-" + suffix, "0".repeat(64));
        Long subscriptionId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        LocalDateTime start = LocalDateTime.ofInstant(Instant.now().minusSeconds(86400), ZoneOffset.UTC);
        LocalDateTime end = LocalDateTime.ofInstant(Instant.now().plusSeconds(86400), ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO mb_subscription_period
                    (tenant_id, subscription_id, plan_id, price_id, price_version_id,
                     currency, base_amount_minor, active_from, period_start, period_end, status)
                VALUES (?, ?, ?, ?, ?, 'CNY', 100, ?, ?, ?, 'ACTIVE')
                """, tenantId, subscriptionId, planId, priceId, versionId, start, start, end);
        return new Fixture(tenantId, otherTenantId, productId, meterId, subscriptionId);
    }

    private UsageEventRequest request(Long subscriptionId, String eventId, Instant occurredAt) {
        return new UsageEventRequest(subscriptionId, "cache-source", eventId,
                "prompt_tokens", "model-cache-test", 5L, occurredAt);
    }

    private record Fixture(Long tenantId, Long otherTenantId, Long productId,
                           Long meterId, Long subscriptionId) {
    }
}
