
package com.meterbill.identity;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthTenantIntegrationTest {

    @Container
    static final MySQLContainer mysql =
            new MySQLContainer("mysql:8.4")
                    .withDatabaseName("meterbill_auth_test")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private TenantRoleSetup roleSetup;

    private String email;
    private final String password = "TestPassword-2026!";
    private Long userId;
    private Long tenantA;
    private Long tenantB;

    @BeforeEach
    void prepareData() {
        String suffix = UUID.randomUUID().toString()
                .replace("-", "").substring(0, 12);

        email = "user-" + suffix + "@example.test";

        jdbc.update("""
                INSERT INTO mb_user(email, password_hash, display_name)
                VALUES (?, ?, ?)
                """, email, encoder.encode(password), "Test User");

        userId = jdbc.queryForObject(
                "SELECT id FROM mb_user WHERE email = ?",
                Long.class, email);

        jdbc.update("""
                INSERT INTO mb_tenant(tenant_code, name)
                VALUES (?, ?)
                """, "tenant-a-" + suffix, "Tenant A");

        jdbc.update("""
                INSERT INTO mb_tenant(tenant_code, name)
                VALUES (?, ?)
                """, "tenant-b-" + suffix, "Tenant B");

        tenantA = jdbc.queryForObject("""
                SELECT id FROM mb_tenant WHERE tenant_code = ?
                """, Long.class, "tenant-a-" + suffix);

        tenantB = jdbc.queryForObject("""
                SELECT id FROM mb_tenant WHERE tenant_code = ?
                """, Long.class, "tenant-b-" + suffix);

        jdbc.update("""
                INSERT INTO mb_tenant_member(tenant_id, user_id)
                VALUES (?, ?)
                """, tenantA, userId);
    }

    private String login() throws Exception {
        String result = mvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(email, password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        return JsonPath.read(result, "$.data.accessToken");
    }

    @Test
    void loginShouldReturnSignedToken() throws Exception {
        String token = login();

        mvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(email))
                .andExpect(jsonPath("$.data.tenants.length()").value(1));
    }

    @Test
    void incorrectPasswordShouldReturn401() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("""
                                {"email":"%s","password":"wrong"}
                                """.formatted(email)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code")
                        .value("INVALID_CREDENTIALS"));
    }

    @Test
    void anonymousRequestShouldReturn401() throws Exception {
        mvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void ownTenantShouldBeAccessible() throws Exception {
        String token = login();

        mvc.perform(get("/api/v1/tenant/current")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-Id", tenantA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(tenantA));
    }

    @Test
    void platformAdminShouldListAndReadActiveTenantsWithoutMembership() throws Exception {
        jdbc.update("INSERT INTO mb_platform_admin(user_id) VALUES (?)", userId);
        String token = login();

        mvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.platformAdmin").value(true))
                .andExpect(jsonPath("$.data.tenants[?(@.tenantId == %s)]".formatted(tenantB)).exists());

        mvc.perform(get("/api/v1/tenant/current")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-Id", tenantB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(tenantB));
    }

    @Test
    void otherTenantShouldReturn403() throws Exception {
        String token = login();

        mvc.perform(get("/api/v1/tenant/current")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-Id", tenantB))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code")
                        .value("TENANT_FORBIDDEN"));
    }

    @Test
    void missingTenantShouldReturn400() throws Exception {
        String token = login();

        mvc.perform(get("/api/v1/tenant/current")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("TENANT_REQUIRED"));
    }

    @Test
    void disabledUserShouldLoseAccess() throws Exception {
        String token = login();

        jdbc.update(
                "UPDATE mb_user SET status = 'DISABLED' WHERE id = ?",
                userId);

        mvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void inactiveTenantShouldReturn403() throws Exception {
        String token = login();

        jdbc.update("""
                UPDATE mb_tenant SET status = 'SUSPENDED'
                WHERE id = ?
                """, tenantA);

        mvc.perform(get("/api/v1/tenant/current")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-Id", tenantA))
                .andExpect(status().isForbidden());
    }

    @Test
    void disabledMembershipShouldReturn403() throws Exception {
        String token = login();

        jdbc.update("""
                UPDATE mb_tenant_member SET status = 'DISABLED'
                WHERE tenant_id = ? AND user_id = ?
                """, tenantA, userId);

        mvc.perform(get("/api/v1/tenant/current")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-Id", tenantA))
                .andExpect(status().isForbidden());
    }

    @Test
    void forgedTokenShouldReturn401() throws Exception {
        mvc.perform(get("/api/v1/auth/me")
                        .header("Authorization",
                                "Bearer invalid.jwt.signature"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tenantOwnerShouldAddChangeAndDisableExistingMember() throws Exception {
        roleSetup.ensureDefaults(tenantA);
        Long actorMemberId = jdbc.queryForObject("""
                SELECT id FROM mb_tenant_member WHERE tenant_id = ? AND user_id = ?
                """, Long.class, tenantA, userId);
        roleSetup.grantLocalOwner(tenantA, actorMemberId);
        String targetEmail = "target-" + UUID.randomUUID() + "@example.test";
        jdbc.update("""
                INSERT INTO mb_user(email, password_hash, display_name)
                VALUES (?, ?, 'Target User')
                """, targetEmail, encoder.encode(password));
        String token = login();

        String body = mvc.perform(post("/api/v1/tenant/security/members")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-Id", tenantA)
                        .contentType("application/json")
                        .content("""
                                {"email":"%s","roleCode":"VIEWER"}
                                """.formatted(targetEmail)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andReturn().getResponse().getContentAsString();
        Long memberId = Long.valueOf(JsonPath.read(body, "$.data").toString());

        mvc.perform(put("/api/v1/tenant/security/members/{memberId}/role", memberId)
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-Id", tenantA)
                        .contentType("application/json")
                        .content("{\"roleCode\":\"ADMIN\"}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/tenant/security/members/{memberId}/disable", memberId)
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-Id", tenantA))
                .andExpect(status().isOk());

        assertEquals("DISABLED", jdbc.queryForObject(
                "SELECT status FROM mb_tenant_member WHERE id = ?", String.class, memberId));
        assertEquals(3L, jdbc.queryForObject("""
                SELECT COUNT(*) FROM mb_security_audit
                WHERE tenant_id = ? AND target_id = ?
                  AND action IN ('MEMBER_ADD','MEMBER_ROLE_CHANGE','MEMBER_DISABLE')
                """, Long.class, tenantA, memberId.toString()));
    }

    @Test
    void ordinaryMemberCannotManageMembersButPlatformAdminCan() throws Exception {
        roleSetup.ensureDefaults(tenantB);
        String targetEmail = "platform-target-" + UUID.randomUUID() + "@example.test";
        jdbc.update("""
                INSERT INTO mb_user(email, password_hash, display_name)
                VALUES (?, ?, 'Platform Target')
                """, targetEmail, encoder.encode(password));
        String token = login();

        mvc.perform(post("/api/v1/tenant/security/members")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-Id", tenantA)
                        .contentType("application/json")
                        .content("""
                                {"email":"%s","roleCode":"VIEWER"}
                                """.formatted(targetEmail)))
                .andExpect(status().isForbidden());

        jdbc.update("INSERT INTO mb_platform_admin(user_id) VALUES (?)", userId);
        token = login();
        mvc.perform(post("/api/v1/tenant/security/members")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-Id", tenantB)
                        .contentType("application/json")
                        .content("""
                                {"email":"%s","roleCode":"VIEWER"}
                                """.formatted(targetEmail)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
    }
}
