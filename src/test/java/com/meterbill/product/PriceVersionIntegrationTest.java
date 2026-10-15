
package com.meterbill.product;

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
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PriceVersionIntegrationTest {

    @Container
    static final MySQLContainer mysql =
            new MySQLContainer("mysql:8.4")
                    .withDatabaseName("meterbill_price_test")
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

    private static final String PASSWORD = "TestPassword-2026!";

    private String adminEmail;
    private String ordinaryEmail;
    private Long productId;
    private Long planId;
    private Long otherPlanId;

    @BeforeEach
    void prepareData() {
        String suffix = UUID.randomUUID()
                .toString().replace("-", "").substring(0, 12);

        String code = suffix.toUpperCase();

        adminEmail = "price-admin-" + suffix + "@example.test";
        ordinaryEmail = "price-user-" + suffix + "@example.test";

        Long adminId = insertUser(adminEmail);
        insertUser(ordinaryEmail);

        jdbc.update("""
                INSERT INTO mb_platform_admin(user_id) VALUES (?)
                """, adminId);

        productId = insertProduct("PRICE_" + code);
        planId = insertPlan(productId, "PRO_" + code);
        otherPlanId = insertPlan(productId, "BASIC_" + code);
    }

    private Long insertUser(String email) {
        jdbc.update("""
                INSERT INTO mb_user(email, password_hash, display_name)
                VALUES (?, ?, ?)
                """, email, encoder.encode(PASSWORD), email);

        return jdbc.queryForObject("""
                SELECT id FROM mb_user WHERE email = ?
                """, Long.class, email);
    }

    private Long insertProduct(String code) {
        jdbc.update("""
                INSERT INTO mb_product(product_code, name, status)
                VALUES (?, ?, 'DRAFT')
                """, code, code);

        return jdbc.queryForObject("""
                SELECT id FROM mb_product WHERE product_code = ?
                """, Long.class, code);
    }

    private Long insertPlan(Long productId, String code) {
        jdbc.update("""
                INSERT INTO mb_plan(product_id, plan_code, name)
                VALUES (?, ?, ?)
                """, productId, code, code);

        return jdbc.queryForObject("""
                SELECT id FROM mb_plan
                WHERE product_id = ? AND plan_code = ?
                """, Long.class, productId, code);
    }

    private String login(String email) throws Exception {
        String response = mvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(email, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return JsonPath.read(response, "$.data.accessToken");
    }

    private String base(Long plan) {
        return "/api/v1/products/" + productId
                + "/plans/" + plan + "/prices";
    }

    private Long createPrice(
            String token, Long plan, String currency,
            String period) throws Exception {

        String json = mvc.perform(post(base(plan))
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("""
                                {"currency":"%s","billingPeriod":"%s"}
                                """.formatted(currency, period)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return ((Number) JsonPath.read(json, "$.data")).longValue();
    }

    private Long createVersion(
            String token, Long plan, Long priceId,
            long amount) throws Exception {

        String json = mvc.perform(
                        post(base(plan) + "/" + priceId + "/versions")
                                .header("Authorization", "Bearer " + token)
                                .contentType("application/json")
                                .content("""
                                        {"baseAmountMinor":%d}
                                        """.formatted(amount)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return ((Number) JsonPath.read(json, "$.data")).longValue();
    }

    private String versionPath(Long priceId, Long versionId) {
        return base(planId) + "/" + priceId
                + "/versions/" + versionId;
    }

    private void publish(
            String token, Long priceId, Long versionId,
            Instant at, int revision) throws Exception {

        mvc.perform(post(versionPath(priceId, versionId) + "/publish")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("""
                                {"effectiveAt":"%s","revision":%d}
                                """.formatted(at, revision)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status")
                        .value("PUBLISHED"));
    }

    @Test
    void createPriceDefinitionShouldWork() throws Exception {
        String token = login(adminEmail);
        Long priceId = createPrice(token, planId, "CNY", "MONTHLY");

        assertNotNull(priceId);

        mvc.perform(get(base(planId))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].currency")
                        .value("CNY"));
    }

    @Test
    void duplicatePriceDimensionShouldReturn409() throws Exception {
        String token = login(adminEmail);
        createPrice(token, planId, "CNY", "MONTHLY");

        mvc.perform(post(base(planId))
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("""
                                {"currency":"CNY","billingPeriod":"MONTHLY"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void sameCurrencyWithDifferentPeriodIsAllowed() throws Exception {
        String token = login(adminEmail);

        createPrice(token, planId, "CNY", "MONTHLY");
        createPrice(token, planId, "CNY", "YEARLY");
        createPrice(token, otherPlanId, "CNY", "MONTHLY");

        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM mb_price
                WHERE currency = 'CNY'
                """, Long.class);

        assertEquals(3L, count);
    }

    @Test
    void newVersionShouldBeDraft() throws Exception {
        String token = login(adminEmail);
        Long priceId = createPrice(token, planId, "USD", "MONTHLY");
        Long versionId = createVersion(token, planId, priceId, 1900);

        mvc.perform(get(base(planId) + "/" + priceId + "/versions")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].id")
                        .value(versionId))
                .andExpect(jsonPath("$.data.records[0].versionNo")
                        .value(1))
                .andExpect(jsonPath("$.data.records[0].status")
                        .value("DRAFT"))
                .andExpect(jsonPath("$.data.records[0].baseAmountMinor")
                        .value(1900));
    }

    @Test
    void secondDraftShouldBeRejected() throws Exception {
        String token = login(adminEmail);
        Long priceId = createPrice(token, planId, "CNY", "MONTHLY");

        createVersion(token, planId, priceId, 9900);

        mvc.perform(post(base(planId) + "/" + priceId + "/versions")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("""
                                {"baseAmountMinor":12900}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void updateDraftShouldUseRevision() throws Exception {
        String token = login(adminEmail);
        Long priceId = createPrice(token, planId, "CNY", "MONTHLY");
        Long versionId = createVersion(token, planId, priceId, 9900);

        mvc.perform(put(versionPath(priceId, versionId))
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("""
                                {"baseAmountMinor":12900,"revision":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision").value(1))
                .andExpect(jsonPath("$.data.baseAmountMinor")
                        .value(12900));

        mvc.perform(put(versionPath(priceId, versionId))
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("""
                                {"baseAmountMinor":19900,"revision":0}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void publishedVersionMustBeImmutable() throws Exception {
        String token = login(adminEmail);
        Long priceId = createPrice(token, planId, "CNY", "MONTHLY");
        Long versionId = createVersion(token, planId, priceId, 9900);

        Instant at = Instant.now().plus(1, ChronoUnit.DAYS)
                .truncatedTo(ChronoUnit.SECONDS);

        publish(token, priceId, versionId, at, 0);

        mvc.perform(put(versionPath(priceId, versionId))
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("""
                                {"baseAmountMinor":19900,"revision":1}
                                """))
                .andExpect(status().isConflict());

        Long amount = jdbc.queryForObject("""
                SELECT base_amount_minor
                FROM mb_price_version WHERE id = ?
                """, Long.class, versionId);
        Long count = jdbc.queryForObject("""
        SELECT COUNT(*)
        FROM mb_security_audit
        WHERE actor_type = 'USER'
          AND action = 'PRICE_VERSION_PUBLISH'
          AND target_type = 'PRICE_VERSION'
          AND target_id = ?
          AND outcome = 'SUCCESS'
        """, Long.class, versionId.toString());

        assertEquals(1L, count);

        assertEquals(9900L, amount);
    }

    @Test
    void effectivePriceMustPreserveHistory() throws Exception {
        String token = login(adminEmail);
        Long priceId = createPrice(token, planId, "CNY", "MONTHLY");

        Instant firstTime = Instant.now().plus(2, ChronoUnit.DAYS)
                .truncatedTo(ChronoUnit.SECONDS);
        Instant secondTime = firstTime.plus(2, ChronoUnit.DAYS);

        Long first = createVersion(token, planId, priceId, 9900);
        publish(token, priceId, first, firstTime, 0);

        Long second = createVersion(token, planId, priceId, 12900);
        publish(token, priceId, second, secondTime, 0);

        mvc.perform(get(base(planId) + "/" + priceId + "/effective")
                        .header("Authorization", "Bearer " + token)
                        .param("at",
                                firstTime.plus(1, ChronoUnit.HOURS).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versionNo").value(1))
                .andExpect(jsonPath("$.data.baseAmountMinor").value(9900));

        mvc.perform(get(base(planId) + "/" + priceId + "/effective")
                        .header("Authorization", "Bearer " + token)
                        .param("at",
                                secondTime.plus(1, ChronoUnit.HOURS).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versionNo").value(2))
                .andExpect(jsonPath("$.data.baseAmountMinor").value(12900));
    }

    @Test
    void priceBeforeFirstEffectiveTimeReturns404() throws Exception {
        String token = login(adminEmail);
        Long priceId = createPrice(token, planId, "CNY", "MONTHLY");
        Long id = createVersion(token, planId, priceId, 9900);

        Instant at = Instant.now().plus(2, ChronoUnit.DAYS)
                .truncatedTo(ChronoUnit.SECONDS);

        publish(token, priceId, id, at, 0);

        mvc.perform(get(base(planId) + "/" + priceId + "/effective")
                        .header("Authorization", "Bearer " + token)
                        .param("at",
                                at.minus(1, ChronoUnit.HOURS).toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    void backdatedPublicationShouldReturn400() throws Exception {
        String token = login(adminEmail);
        Long priceId = createPrice(token, planId, "CNY", "MONTHLY");
        Long id = createVersion(token, planId, priceId, 9900);

        mvc.perform(post(versionPath(priceId, id) + "/publish")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("""
                                {"effectiveAt":"2020-01-01T00:00:00Z",
                                 "revision":0}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void publishedTimesMustIncrease() throws Exception {
        String token = login(adminEmail);
        Long priceId = createPrice(token, planId, "CNY", "MONTHLY");

        Instant firstTime = Instant.now().plus(5, ChronoUnit.DAYS)
                .truncatedTo(ChronoUnit.SECONDS);

        Long first = createVersion(token, planId, priceId, 9900);
        publish(token, priceId, first, firstTime, 0);

        Long second = createVersion(token, planId, priceId, 12900);

        mvc.perform(post(versionPath(priceId, second) + "/publish")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("""
                                {"effectiveAt":"%s","revision":0}
                                """.formatted(
                                firstTime.minus(1, ChronoUnit.DAYS))))
                .andExpect(status().isConflict());
    }

    @Test
    void wrongPlanCannotAccessPrice() throws Exception {
        String token = login(adminEmail);
        Long priceId = createPrice(token, planId, "CNY", "MONTHLY");

        mvc.perform(get(base(otherPlanId) + "/" + priceId + "/versions")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void ordinaryUserAndAnonymousMustBeRejected() throws Exception {
        Long priceId = createPrice(
                login(adminEmail), planId, "CNY", "MONTHLY");

        mvc.perform(get(base(planId) + "/" + priceId + "/versions")
                        .header("Authorization",
                                "Bearer " + login(ordinaryEmail)))
                .andExpect(status().isForbidden());

        mvc.perform(get(base(planId)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidCurrencyAndNegativeAmountMustFail() throws Exception {
        String token = login(adminEmail);

        mvc.perform(post(base(planId))
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("""
                                {"currency":"INVALID",
                                 "billingPeriod":"MONTHLY"}
                                """))
                .andExpect(status().isBadRequest());

        Long priceId = createPrice(token, planId, "CNY", "MONTHLY");

        mvc.perform(post(base(planId) + "/" + priceId + "/versions")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("""
                                {"baseAmountMinor":-1}
                                """))
                .andExpect(status().isBadRequest());
    }
    @Test
    void ruleModeReplacementClearsNullableUnitPriceAndKeepsPublishedVersionImmutable() throws Exception {
        String token = login(adminEmail);
        Long price = createPrice(token, planId, "CNY", "MONTHLY");
        Long version = createVersion(token, planId, price, 1000);
        jdbc.update("INSERT INTO mb_usage_meter(product_id,meter_code,name,unit) VALUES (?,'INPUT','Input','TOKEN')", productId);
        Long meter = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        String path = versionPath(price, version) + "/usage-rules/" + meter;
        String[] bodies = {
                "{\"mode\":\"PER_UNIT\",\"includedQuantity\":0,\"unitAmountMinor\":3,\"tiers\":[],\"revision\":0}",
                "{\"mode\":\"GRADUATED\",\"includedQuantity\":0,\"tiers\":[{\"upToQuantity\":10,\"unitAmountMinor\":2},{\"unitAmountMinor\":1}],\"revision\":1}",
                "{\"mode\":\"VOLUME\",\"includedQuantity\":0,\"tiers\":[{\"unitAmountMinor\":2}],\"revision\":2}",
                "{\"mode\":\"PER_UNIT\",\"includedQuantity\":0,\"unitAmountMinor\":4,\"tiers\":[],\"revision\":3}"};
        for (int i = 0; i < bodies.length; i++) {
            mvc.perform(put(path).header("Authorization", "Bearer " + token)
                            .contentType("application/json").content(bodies[i]))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.priceVersionRevision").value(i + 1));
            Long unit = jdbc.queryForObject("SELECT unit_amount_minor FROM mb_usage_price_rule WHERE price_version_id=?",
                    Long.class, version);
            if (i == 1 || i == 2) assertNull(unit); else assertEquals(i == 0 ? 3L : 4L, unit);
        }
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM mb_usage_price_tier t JOIN mb_usage_price_rule r ON r.id=t.rule_id WHERE r.price_version_id=?
                """, Integer.class, version));
        publish(token, price, version, Instant.now().plusSeconds(60), 4);
        mvc.perform(put(path).header("Authorization", "Bearer " + token)
                        .contentType("application/json").content(bodies[0].replace("\"revision\":0", "\"revision\":5")))
                .andExpect(status().isConflict());
    }
}
