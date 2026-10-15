package com.meterbill.metering;

import com.meterbill.messaging.ConfirmedRabbitPublisher;
import com.meterbill.messaging.MessagingTopology;
import com.meterbill.messaging.OutboxDispatcher;
import com.meterbill.metering.service.UsageEventProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UsageEventIntegrationTest {

    @Container
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4")
            .withDatabaseName("meterbill_metering_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static final GenericContainer<?> rabbit = new GenericContainer<>(
            DockerImageName.parse("rabbitmq:4.1.8-management-alpine"))
            .withEnv("RABBITMQ_DEFAULT_USER", "test")
            .withEnv("RABBITMQ_DEFAULT_PASS", "test")
            .withExposedPorts(5672)
            .waitingFor(Wait.forListeningPort());

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.rabbitmq.host", rabbit::getHost);
        registry.add("spring.rabbitmq.port", () -> rabbit.getMappedPort(5672));
        registry.add("spring.rabbitmq.username", () -> "test");
        registry.add("spring.rabbitmq.password", () -> "test");
        registry.add("meterbill.messaging.enabled", () -> true);
        registry.add("meterbill.messaging.dispatch-interval", () -> 60_000);
        registry.add("meterbill.messaging.retry-delay-ms", () -> 100);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OutboxDispatcher outbox;

    @Autowired
    private ConfirmedRabbitPublisher publisher;

    @Autowired
    private UsageEventProcessor processor;

    private Fixture fixture;

    @BeforeEach
    void setUp() throws Exception {
        fixture = createFixture();
    }

    @Test
    void acceptsIdempotentlyAndRejectsConflictingPayloads() throws Exception {
        String payload = payload(fixture, 73L);
        mvc.perform(post("/api/v1/ingest/usage-events")
                        .header("X-API-Key", fixture.apiKey())
                        .contentType("application/json")
                        .content(payload))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("RECEIVED"));

        mvc.perform(post("/api/v1/ingest/usage-events")
                        .header("X-API-Key", fixture.apiKey())
                        .contentType("application/json")
                        .content(payload))
                .andExpect(status().isAccepted());

        mvc.perform(post("/api/v1/ingest/usage-events")
                        .header("X-API-Key", fixture.apiKey())
                        .contentType("application/json")
                        .content(payload(fixture, 74L)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_CONFLICT"));

        assertEquals(1, count("mb_usage_event", fixture.tenantId()));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM mb_outbox o
                JOIN mb_usage_event e ON e.id = o.event_id
                WHERE e.tenant_id = ?
                """, Integer.class, fixture.tenantId()));
    }

    @Test
    void confirmedOutboxDeliveryAggregatesOnceEvenWhenMessageIsRepeated() throws Exception {
        mvc.perform(post("/api/v1/ingest/usage-events")
                        .header("X-API-Key", fixture.apiKey())
                        .contentType("application/json")
                        .content(payload(fixture, 91L)))
                .andExpect(status().isAccepted());

        Long eventId = jdbc.queryForObject("""
                SELECT id FROM mb_usage_event WHERE tenant_id = ? AND event_id = ?
                """, Long.class, fixture.tenantId(), fixture.eventId());
        outbox.dispatchPending();
        awaitStatus(eventId, "PROCESSED");
        assertEquals(91L, totalQuantity(fixture));

        publisher.publish(MessagingTopology.USAGE_EXCHANGE,
                MessagingTopology.USAGE_ROUTING_KEY, eventId.toString(), 0);
        awaitDeliveryCount(eventId, 2);
        assertEquals(91L, totalQuantity(fixture));

        mvc.perform(get("/api/v1/ingest/usage-events/{source}/{event}",
                        fixture.sourceId(), fixture.eventId())
                        .header("X-API-Key", fixture.apiKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PROCESSED"))
                .andExpect(jsonPath("$.data.deliveryCount").value(2));
    }

    @Test
    void recordsRetryableFailureAndAllowsTheEventToBeProcessedAgain() throws Exception {
        mvc.perform(post("/api/v1/ingest/usage-events")
                        .header("X-API-Key", fixture.apiKey())
                        .contentType("application/json")
                        .content(payload(fixture, 37L)))
                .andExpect(status().isAccepted());

        Long eventId = jdbc.queryForObject("""
                SELECT id FROM mb_usage_event WHERE tenant_id = ? AND event_id = ?
                """, Long.class, fixture.tenantId(), fixture.eventId());
        processor.recordFailure(eventId, false, "transient test failure");
        assertEquals("RETRYABLE_FAILED", jdbc.queryForObject(
                "SELECT status FROM mb_usage_event WHERE id = ?", String.class, eventId));
        assertNotNull(jdbc.queryForObject(
                "SELECT last_error FROM mb_usage_event WHERE id = ?", String.class, eventId));

        processor.process(eventId);
        assertEquals("PROCESSED", jdbc.queryForObject(
                "SELECT status FROM mb_usage_event WHERE id = ?", String.class, eventId));
        assertEquals(37L, totalQuantity(fixture));
    }

    private Fixture createFixture() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String rawKey = "mbk_" + suffix.substring(0, 16) + "_"
                + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8))
                .replace("=", "");
        while (rawKey.length() < 64) {
            rawKey += "A";
        }
        rawKey = rawKey.substring(0, 64);

        jdbc.update("INSERT INTO mb_user(email, password_hash, display_name) VALUES (?, ?, ?)",
                "metering-" + suffix + "@example.test", "unused", "Metering Test");
        Long userId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO mb_tenant(tenant_code, name) VALUES (?, ?)",
                "metering-" + suffix, "Metering Test");
        Long tenantId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO mb_tenant_member(tenant_id, user_id) VALUES (?, ?)", tenantId, userId);
        Long memberId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        String prefix = rawKey.substring(4, 20);
        jdbc.update("""
                INSERT INTO mb_api_key
                    (tenant_id, created_by_member_id, name, key_prefix, key_hash, expires_at)
                VALUES (?, ?, 'Metering Test', ?, ?, ?)
                """, tenantId, memberId, prefix, sha256(rawKey),
                LocalDateTime.ofInstant(Instant.now().plusSeconds(3600), ZoneOffset.UTC));

        jdbc.update("""
                INSERT INTO mb_product(product_code, name, status)
                VALUES (?, 'Metering Product', 'ACTIVE')
                """, "metering-product-" + suffix);
        Long productId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO mb_plan(product_id, plan_code, name, status)
                VALUES (?, ?, 'Metering Plan', 'ACTIVE')
                """, productId, "metering-plan-" + suffix);
        Long planId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO mb_price(plan_id, currency, billing_period)
                VALUES (?, 'CNY', 'MONTHLY')
                """, planId);
        Long priceId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO mb_price_version
                    (price_id, version_no, base_amount_minor, status, effective_at)
                VALUES (?, 1, 0, 'PUBLISHED', ?)
                """, priceId, LocalDateTime.ofInstant(Instant.now().minusSeconds(3600), ZoneOffset.UTC));
        Long priceVersionId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO mb_usage_meter(product_id, meter_code, name, unit)
                VALUES (?, 'input_tokens', 'Input Tokens', 'token')
                """, productId);
        Long meterId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO mb_usage_price_rule(price_version_id,meter_id,pricing_mode,included_quantity,unit_amount_minor) VALUES (?,?,'PER_UNIT',0,1)", priceVersionId, meterId);
        jdbc.update("INSERT INTO mb_ai_model(product_id,model_code,name) VALUES (?,?,'Test model')", productId, "model-a");
        Long modelId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO mb_model_meter(model_id,meter_id) VALUES (?,?)", modelId, meterId);

        String idem = "metering-" + suffix;
        jdbc.update("""
                INSERT INTO mb_subscription(tenant_id, plan_id, idempotency_key, request_hash, status)
                VALUES (?, ?, ?, ?, 'ACTIVE')
                """, tenantId, planId, idem, "0".repeat(64));
        Long subscriptionId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO mb_subscription_period
                    (tenant_id, subscription_id, plan_id, price_id, price_version_id,
                     currency, base_amount_minor, active_from, period_start, period_end, status)
                VALUES (?, ?, ?, ?, ?, 'CNY', 0, ?, ?, ?, 'ACTIVE')
                """, tenantId, subscriptionId, planId, priceId, priceVersionId,
                LocalDateTime.ofInstant(now.minusSeconds(60L * 86400), ZoneOffset.UTC),
                LocalDateTime.ofInstant(now.minusSeconds(30L * 86400), ZoneOffset.UTC),
                LocalDateTime.ofInstant(now.plusSeconds(30L * 86400), ZoneOffset.UTC));
        Long periodId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        String sourceId = "source-" + suffix.substring(0, 10);
        String eventId = "event-" + suffix.substring(0, 12);
        return new Fixture(tenantId, subscriptionId, periodId, meterId, sourceId, eventId,
                rawKey, Instant.now().minusSeconds(10));
    }

    private String payload(Fixture value, long quantity) {
        return """
                {"subscriptionId":%d,"sourceId":"%s","eventId":"%s",
                 "meterCode":"input_tokens","modelCode":"model-a","quantity":%d,
                 "occurredAt":"%s"}
                """.formatted(value.subscriptionId(), value.sourceId(), value.eventId(),
                quantity, value.occurredAt());
    }

    private int count(String table, Long tenantId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE tenant_id = ?",
                Integer.class, tenantId);
    }

    private long totalQuantity(Fixture value) {
        Long total = jdbc.queryForObject("""
                SELECT total_quantity FROM mb_usage_period_total
                WHERE tenant_id = ? AND subscription_id = ? AND period_id = ? AND meter_id = ?
                """, Long.class, value.tenantId(), value.subscriptionId(),
                value.periodId(), value.meterId());
        return total == null ? 0 : total;
    }

    private void awaitStatus(Long eventId, String expected) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(20);
        while (Instant.now().isBefore(deadline)) {
            String status = jdbc.queryForObject(
                    "SELECT status FROM mb_usage_event WHERE id = ?", String.class, eventId);
            if (expected.equals(status)) {
                return;
            }
            Thread.sleep(100);
        }
        assertEquals(expected, jdbc.queryForObject(
                "SELECT status FROM mb_usage_event WHERE id = ?", String.class, eventId));
    }

    private void awaitDeliveryCount(Long eventId, int expected) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(20);
        while (Instant.now().isBefore(deadline)) {
            Integer count = jdbc.queryForObject(
                    "SELECT delivery_count FROM mb_usage_event WHERE id = ?", Integer.class, eventId);
            if (count != null && count >= expected) {
                return;
            }
            Thread.sleep(100);
        }
        assertEquals(expected, jdbc.queryForObject(
                "SELECT delivery_count FROM mb_usage_event WHERE id = ?", Integer.class, eventId));
    }

    private byte[] sha256(String value) throws Exception {
        return MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void asynchronousPublisherDoesNotTreatUnroutableConfirmationAsSuccess() {
        org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.CompletionException.class,
                () -> publisher.publishAsync(MessagingTopology.USAGE_EXCHANGE,"missing-route","1",0).join());
    }
    @Test
    void twoDispatchersPublishBatchWithoutLosingOrDoubleAggregatingEvents() throws Exception {
        for(int i=0;i<24;i++) {
            String body=payload(fixture,1L).replace(fixture.eventId(),fixture.eventId()+"-"+i);
            mvc.perform(post("/api/v1/ingest/usage-events").header("X-API-Key",fixture.apiKey())
                    .contentType("application/json").content(body)).andExpect(status().isAccepted());
        }
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->{start.await();outbox.dispatchPending();return true;});
            var second=pool.submit(()->{start.await();outbox.dispatchPending();return true;});
            start.countDown();first.get(30,java.util.concurrent.TimeUnit.SECONDS);second.get(30,java.util.concurrent.TimeUnit.SECONDS);
        }
        for(Long id:jdbc.queryForList("SELECT id FROM mb_usage_event WHERE tenant_id=?",Long.class,fixture.tenantId())) awaitStatus(id,"PROCESSED");
        assertEquals(24L,totalQuantity(fixture));
        assertEquals(24,jdbc.queryForObject("SELECT COUNT(*) FROM mb_outbox o JOIN mb_usage_event e ON e.id=o.event_id WHERE e.tenant_id=? AND o.status='PUBLISHED'",Integer.class,fixture.tenantId()));
    }
    @Test
    void simultaneousDuplicateIngestReturnsAcceptedFactToEveryCaller() throws Exception {
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var results=new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for(int i=0;i<8;i++) results.add(pool.submit(()->{start.await();return mvc.perform(post("/api/v1/ingest/usage-events")
                    .header("X-API-Key",fixture.apiKey()).contentType("application/json").content(payload(fixture,19L))).andReturn().getResponse().getStatus();}));
            start.countDown();
            for(var result:results) assertEquals(202,result.get(20,java.util.concurrent.TimeUnit.SECONDS));
        }
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mb_usage_event WHERE tenant_id=?",Integer.class,fixture.tenantId()));
        outbox.dispatchPending();
        Long id=jdbc.queryForObject("SELECT id FROM mb_usage_event WHERE tenant_id=?",Long.class,fixture.tenantId());
        awaitStatus(id,"PROCESSED");assertEquals(19L,totalQuantity(fixture));
    }
    private record Fixture(Long tenantId, Long subscriptionId, Long periodId, Long meterId,
                           String sourceId, String eventId, String apiKey, Instant occurredAt) {
    }
    @Test
    void processDiesAfterCommitBeforeAckAndRestartRedeliversWithoutDoubleAggregation() throws Exception {
        mvc.perform(post("/api/v1/ingest/usage-events").header("X-API-Key", fixture.apiKey())
                        .contentType("application/json").content(payload(fixture, 47L)))
                .andExpect(status().isAccepted());
        Long id = jdbc.queryForObject("SELECT id FROM mb_usage_event WHERE tenant_id=?", Long.class, fixture.tenantId());
        // Keep this fixture away from the scheduled dispatcher; publish to a dedicated real broker queue.
        jdbc.update("UPDATE mb_outbox SET next_retry_at=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 DAY) WHERE event_id=?", id);
        var factory = new com.rabbitmq.client.ConnectionFactory();
        factory.setHost(rabbit.getHost()); factory.setPort(rabbit.getMappedPort(5672));
        factory.setUsername("test"); factory.setPassword("test");
        String queue = "commit-before-ack-" + UUID.randomUUID();
        java.nio.file.Path log = java.nio.file.Path.of("target", queue + ".log");
        try (var connection = factory.newConnection(); var channel = connection.createChannel()) {
            channel.queueDeclare(queue, true, false, false, null);
            channel.basicPublish("", queue, new com.rabbitmq.client.AMQP.BasicProperties.Builder().deliveryMode(2).build(),
                    id.toString().getBytes(StandardCharsets.UTF_8));
            String javaExecutable = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString();
            var builder = new ProcessBuilder(javaExecutable, "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                    CommitBeforeAckWorker.class.getName(), queue).redirectErrorStream(true).redirectOutput(log.toFile());
            builder.environment().put("ACK_JDBC_URL", mysql.getJdbcUrl());
            builder.environment().put("ACK_RABBIT_HOST", rabbit.getHost());
            builder.environment().put("ACK_RABBIT_PORT", rabbit.getMappedPort(5672).toString());
            Process child = builder.start();
            try {
                org.junit.jupiter.api.Assertions.assertTrue(child.waitFor(40, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(73, child.exitValue(), java.nio.file.Files.readString(log));
            } finally { if (child.isAlive()) child.destroyForcibly(); }
            assertEquals("PROCESSED", jdbc.queryForObject("SELECT status FROM mb_usage_event WHERE id=?", String.class, id));
            assertEquals(47L, totalQuantity(fixture));
            com.rabbitmq.client.GetResponse redelivery = null;
            for (int i=0; i<100 && redelivery==null; i++) { redelivery=channel.basicGet(queue, false); if(redelivery==null) Thread.sleep(50); }
            assertNotNull(redelivery);
            org.junit.jupiter.api.Assertions.assertTrue(redelivery.getEnvelope().isRedeliver());
            var props = new org.springframework.amqp.core.MessageProperties();
            props.setDeliveryTag(redelivery.getEnvelope().getDeliveryTag());
            new com.meterbill.messaging.UsageEventListener(processor, publisher)
                    .consume(new org.springframework.amqp.core.Message(redelivery.getBody(), props), channel);
            assertEquals(47L, totalQuantity(fixture));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mb_usage_event_processed WHERE usage_event_id=?", Integer.class, id));
            assertEquals(2, jdbc.queryForObject("SELECT delivery_count FROM mb_usage_event WHERE id=?", Integer.class, id));
            assertEquals(0, channel.queueDeclarePassive(queue).getMessageCount());
            channel.queueDelete(queue);
        }
    }
}

