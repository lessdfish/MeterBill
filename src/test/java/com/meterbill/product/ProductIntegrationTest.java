
package com.meterbill.product;

import com.meterbill.identity.bootstrap.TenantRoleSetup;
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

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductIntegrationTest {

    @Container
    static final MySQLContainer mysql =
            new MySQLContainer("mysql:8.4")
                    .withDatabaseName("meterbill_product_test")
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
    private String productCode;
    private Long tenantId;

    @BeforeEach
    void prepareData() {
        String suffix = UUID.randomUUID().toString()
                .replace("-", "")
                .substring(0, 10)
                .toUpperCase();

        adminEmail = "platform-" + suffix + "@example.test";
        ownerEmail = "owner-" + suffix + "@example.test";
        productCode = "AI_API_" + suffix;

        Long adminId = createUser(adminEmail);
        Long ownerId = createUser(ownerEmail);

        jdbc.update("""
                INSERT INTO mb_platform_admin(user_id)
                VALUES (?)
                """, adminId);

        String tenantCode = "tenant-" + suffix;

        jdbc.update("""
                INSERT INTO mb_tenant(tenant_code, name)
                VALUES (?, ?)
                """, tenantCode, "Test Tenant");

        tenantId = jdbc.queryForObject("""
                SELECT id FROM mb_tenant WHERE tenant_code = ?
                """, Long.class, tenantCode);

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

    private Long createUser(String email) {
        jdbc.update("""
                INSERT INTO mb_user(email, password_hash, display_name)
                VALUES (?, ?, ?)
                """, email, encoder.encode(PASSWORD), email);

        return jdbc.queryForObject("""
                SELECT id FROM mb_user WHERE email = ?
                """, Long.class, email);
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

    private String productJson(String code) {
        return """
                {
                  "productCode": "%s",
                  "name": "AI API Service",
                  "description": "AI inference service"
                }
                """.formatted(code);
    }

    @Test
    void platformAdminCanCreateDraftProduct() throws Exception {
        String token = login(adminEmail);

        mvc.perform(post("/api/v1/products")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(productJson(productCode)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data").isNumber());

        String status = jdbc.queryForObject("""
                SELECT status FROM mb_product
                WHERE product_code = ?
                """, String.class, productCode);

        assertEquals("DRAFT", status);

        Long timestamps = jdbc.queryForObject("""
                SELECT COUNT(*) FROM mb_product
                WHERE product_code = ?
                  AND created_at IS NOT NULL
                  AND updated_at IS NOT NULL
                """, Long.class, productCode);

        assertEquals(1L, timestamps);
    }

    @Test
    void duplicateProductCodeReturns409() throws Exception {
        String token = login(adminEmail);

        mvc.perform(post("/api/v1/products")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(productJson(productCode)))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/products")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(productJson(productCode)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("BUSINESS_CONFLICT"));
    }

    @Test
    void invalidProductReturns400() throws Exception {
        String token = login(adminEmail);

        mvc.perform(post("/api/v1/products")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("""
                                {
                                  "productCode": "",
                                  "name": ""
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("INVALID_ARGUMENT"));
    }

    @Test
    void tenantOwnerCannotCreatePlatformProduct() throws Exception {
        String token = login(ownerEmail);

        mvc.perform(post("/api/v1/products")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-Id", tenantId)
                        .contentType("application/json")
                        .content(productJson(productCode)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void tenantOwnerCannotListPlatformProducts() throws Exception {
        String token = login(ownerEmail);

        mvc.perform(get("/api/v1/products")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-Id", tenantId))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousRequestReturns401() throws Exception {
        mvc.perform(get("/api/v1/products"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void platformProductListIsPaged() throws Exception {
        String token = login(adminEmail);

        for (int i = 1; i <= 2; i++) {
            mvc.perform(post("/api/v1/products")
                            .header("Authorization", "Bearer " + token)
                            .contentType("application/json")
                            .content(productJson(productCode + "_" + i)))
                    .andExpect(status().isOk());
        }

        mvc.perform(get("/api/v1/products")
                        .header("Authorization", "Bearer " + token)
                        .param("page", "1")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(1))
                .andExpect(jsonPath("$.data.records.length()").value(1));
    }

    @Test
    void invalidPaginationReturns400() throws Exception {
        String token = login(adminEmail);

        mvc.perform(get("/api/v1/products")
                        .header("Authorization", "Bearer " + token)
                        .param("page", "0")
                        .param("size", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("INVALID_ARGUMENT"));
    }
}
