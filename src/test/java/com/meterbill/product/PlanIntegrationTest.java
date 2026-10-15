
package com.meterbill.product;

import com.jayway.jsonpath.JsonPath;
import com.meterbill.identity.bootstrap.TenantRoleSetup;
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

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlanIntegrationTest {

    @Container
    static final MySQLContainer mysql =
            new MySQLContainer("mysql:8.4")
                    .withDatabaseName("meterbill_plan_test")
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
    @Autowired private TenantRoleSetup roleSetup;

    private static final String PASSWORD = "TestPassword-2026!";

    private String adminEmail;
    private String ownerEmail;
    private String planCode;
    private Long productA;
    private Long productB;

    @BeforeEach
    void prepareData() {
        String suffix = UUID.randomUUID().toString()
                .replace("-", "")
                .substring(0, 10);

        String code = suffix.toUpperCase();

        adminEmail = "plan-admin-" + suffix + "@example.test";
        ownerEmail = "plan-owner-" + suffix + "@example.test";
        planCode = "PRO_" + code;

        Long adminId = insertUser(adminEmail);
        Long ownerId = insertUser(ownerEmail);

        jdbc.update("""
                INSERT INTO mb_platform_admin(user_id)
                VALUES (?)
                """, adminId);

        productA = insertProduct("AI_API_" + code);
        productB = insertProduct("STORAGE_" + code);

        jdbc.update("""
                INSERT INTO mb_tenant(tenant_code, name)
                VALUES (?, ?)
                """, "tenant-" + suffix, "Test Tenant");

        Long tenantId = jdbc.queryForObject("""
                SELECT id FROM mb_tenant WHERE tenant_code = ?
                """, Long.class, "tenant-" + suffix);

        jdbc.update("""
                INSERT INTO mb_tenant_member(tenant_id, user_id)
                VALUES (?, ?)
                """, tenantId, ownerId);

        Long memberId = jdbc.queryForObject("""
                SELECT id FROM mb_tenant_member
                WHERE tenant_id = ? AND user_id = ?
                """, Long.class, tenantId, ownerId);

        roleSetup.grantLocalOwner(tenantId, memberId);
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

    private Long insertPlan(
            Long productId, String code, String status) {

        jdbc.update("""
                INSERT INTO mb_plan
                    (product_id, plan_code, name, status)
                VALUES (?, ?, ?, ?)
                """, productId, code, code, status);

        return jdbc.queryForObject("""
                SELECT id FROM mb_plan
                WHERE product_id = ? AND plan_code = ?
                """, Long.class, productId, code);
    }

    private String login(String email) throws Exception {
        String json = mvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(email, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return JsonPath.read(json, "$.data.accessToken");
    }

    private String createBody(String code) {
        return """
                {
                  "planCode": "%s",
                  "name": "Professional Plan",
                  "description": "Professional tier"
                }
                """.formatted(code);
    }

    private String updateBody(int version) {
        return """
                {
                  "name": "Professional Plus",
                  "description": "Updated plan",
                  "version": %d
                }
                """.formatted(version);
    }

    private Long createPlanByApi(
            String token, Long productId, String code) throws Exception {

        String json = mvc.perform(
                        post("/api/v1/products/{productId}/plans", productId)
                                .header("Authorization", "Bearer " + token)
                                .contentType("application/json")
                                .content(createBody(code)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Number id = JsonPath.read(json, "$.data");
        return id.longValue();
    }

    @Test
    void createShouldProduceDraftPlan() throws Exception {
        String token = login(adminEmail);
        Long id = createPlanByApi(token, productA, planCode);

        mvc.perform(get(
                        "/api/v1/products/{productId}/plans/{planId}",
                        productA, id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.planCode").value(planCode))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.version").value(0));

        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM mb_plan
                WHERE id = ?
                  AND created_at IS NOT NULL
                  AND updated_at IS NOT NULL
                """, Long.class, id);

        assertEquals(1L, count);
    }

    @Test
    void duplicateCodeWithinProductReturns409() throws Exception {
        String token = login(adminEmail);
        createPlanByApi(token, productA, planCode);

        mvc.perform(post(
                        "/api/v1/products/{productId}/plans", productA)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(createBody(planCode)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("BUSINESS_CONFLICT"));
    }

    @Test
    void sameCodeAcrossProductsIsAllowed() throws Exception {
        String token = login(adminEmail);

        Long first = createPlanByApi(token, productA, planCode);
        Long second = createPlanByApi(token, productB, planCode);

        assertNotEquals(first, second);
    }

    @Test
    void nonexistentProductReturns404() throws Exception {
        String token = login(adminEmail);

        mvc.perform(post(
                        "/api/v1/products/{productId}/plans",
                        Long.MAX_VALUE)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(createBody(planCode)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void tenantOwnerCannotCreatePlatformPlan() throws Exception {
        String token = login(ownerEmail);

        mvc.perform(post(
                        "/api/v1/products/{productId}/plans", productA)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(createBody(planCode)))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousRequestReturns401() throws Exception {
        mvc.perform(get(
                        "/api/v1/products/{productId}/plans", productA))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidCreateRequestReturns400() throws Exception {
        String token = login(adminEmail);

        mvc.perform(post(
                        "/api/v1/products/{productId}/plans", productA)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("""
                                {"planCode":"","name":""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("INVALID_ARGUMENT"));
    }

    @Test
    void listShouldBeProductScopedAndPaged() throws Exception {
        String token = login(adminEmail);

        createPlanByApi(token, productA, planCode);
        createPlanByApi(token, productA, "BASIC_" + planCode);
        createPlanByApi(token, productB, planCode);

        mvc.perform(get(
                        "/api/v1/products/{productId}/plans", productA)
                        .header("Authorization", "Bearer " + token)
                        .param("page", "1")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.records.length()").value(1));
    }

    @Test
    void detailShouldRejectWrongProduct() throws Exception {
        String token = login(adminEmail);
        Long planId = createPlanByApi(token, productA, planCode);

        mvc.perform(get(
                        "/api/v1/products/{productId}/plans/{planId}",
                        productB, planId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void draftUpdateShouldIncrementVersion() throws Exception {
        String token = login(adminEmail);
        Long planId = createPlanByApi(token, productA, planCode);

        mvc.perform(put(
                        "/api/v1/products/{productId}/plans/{planId}",
                        productA, planId)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(updateBody(0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name")
                        .value("Professional Plus"))
                .andExpect(jsonPath("$.data.version").value(1));
    }

    @Test
    void staleVersionShouldReturn409() throws Exception {
        String token = login(adminEmail);
        Long planId = createPlanByApi(token, productA, planCode);

        String path = "/api/v1/products/{productId}/plans/{planId}";

        mvc.perform(put(path, productA, planId)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(updateBody(0)))
                .andExpect(status().isOk());

        mvc.perform(put(path, productA, planId)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(updateBody(0)))
                .andExpect(status().isConflict());

        Integer version = jdbc.queryForObject("""
                SELECT version FROM mb_plan WHERE id = ?
                """, Integer.class, planId);

        assertEquals(1, version);
    }

    @Test
    void activePlanCannotBeEdited() throws Exception {
        String token = login(adminEmail);
        Long planId = insertPlan(productA, planCode, "ACTIVE");

        mvc.perform(put(
                        "/api/v1/products/{productId}/plans/{planId}",
                        productA, planId)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(updateBody(0)))
                .andExpect(status().isConflict());
    }

    @Test
    void platformAdminCanRetirePlanAndActionIsAudited() throws Exception {
        String token = login(adminEmail);
        Long planId = insertPlan(productA, planCode, "ACTIVE");

        mvc.perform(post(
                        "/api/v1/products/{productId}/plans/{planId}/retire",
                        productA, planId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RETIRED"))
                .andExpect(jsonPath("$.data.version").value(1));

        assertEquals(1L, jdbc.queryForObject("""
                SELECT COUNT(*) FROM mb_security_audit
                WHERE action = 'PLAN_RETIRE'
                  AND target_type = 'PLAN'
                  AND target_id = ?
                """, Long.class, planId.toString()));

        mvc.perform(post(
                        "/api/v1/products/{productId}/plans/{planId}/retire",
                        productA, planId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
    }

    @Test
    void tenantOwnerCannotRetirePlatformPlan() throws Exception {
        String token = login(ownerEmail);
        Long planId = insertPlan(productA, planCode, "ACTIVE");

        mvc.perform(post(
                        "/api/v1/products/{productId}/plans/{planId}/retire",
                        productA, planId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void retireShouldRejectPlanFromAnotherProduct() throws Exception {
        String token = login(adminEmail);
        Long planId = insertPlan(productA, planCode, "ACTIVE");

        mvc.perform(post(
                        "/api/v1/products/{productId}/plans/{planId}/retire",
                        productB, planId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void archivedProductCannotCreatePlan() throws Exception {
        String token = login(adminEmail);

        jdbc.update("""
                UPDATE mb_product SET status = 'ARCHIVED'
                WHERE id = ?
                """, productA);

        mvc.perform(post(
                        "/api/v1/products/{productId}/plans", productA)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(createBody(planCode)))
                .andExpect(status().isConflict());
    }

    @Test
    void invalidPaginationReturns400() throws Exception {
        String token = login(adminEmail);

        mvc.perform(get(
                        "/api/v1/products/{productId}/plans", productA)
                        .header("Authorization", "Bearer " + token)
                        .param("page", "0")
                        .param("size", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("INVALID_ARGUMENT"));
    }

    @Test
    void productAndPlanPublicationRequireAnEffectiveMonthlyPrice()
            throws Exception {
        String token = login(adminEmail);
        Long planId = insertPlan(productA, planCode, "DRAFT");

        mvc.perform(post("/api/v1/products/{productId}/publish", productA)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_CONFLICT"));

        String publishPlanPath =
                "/api/v1/products/{productId}/plans/{planId}/publish";
        mvc.perform(post(publishPlanPath, productA, planId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_CONFLICT"));

        jdbc.update("""
                INSERT INTO mb_price(plan_id, currency, billing_period)
                VALUES (?, 'CNY', 'MONTHLY')
                """, planId);
        Long priceId = jdbc.queryForObject("""
                SELECT id FROM mb_price WHERE plan_id = ?
                """, Long.class, planId);
        jdbc.update("""
                INSERT INTO mb_price_version
                    (price_id, version_no, base_amount_minor, status, effective_at)
                VALUES (?, 1, 19900, 'PUBLISHED', UTC_TIMESTAMP(6))
                """, priceId);

        mvc.perform(post(publishPlanPath, productA, planId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        mvc.perform(post("/api/v1/products/{productId}/publish", productA)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        assertEquals(2L, jdbc.queryForObject("""
                SELECT COUNT(*) FROM mb_security_audit
                WHERE action IN ('PLAN_PUBLISH', 'PRODUCT_PUBLISH')
                  AND target_id IN (?, ?)
                """, Long.class, planId.toString(), productA.toString()));
    }
}
