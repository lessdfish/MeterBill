
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

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityLifecycleIntegrationTest {

    @Container
    static final MySQLContainer mysql =
            new MySQLContainer("mysql:8.4")
                    .withDatabaseName("meterbill_security_test")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired TenantRoleSetup roleSetup;

    private static final String PASSWORD = "TestPassword-2026!";

    private String ownerEmail;
    private String viewerEmail;
    private Long ownerMemberId;
    private Long viewerMemberId;
    private Long tenantA;
    private Long tenantB;

    @BeforeEach
    void prepareData() {
        String suffix = UUID.randomUUID().toString()
                .replace("-", "").substring(0, 12);

        ownerEmail = "owner-" + suffix + "@example.test";
        viewerEmail = "viewer-" + suffix + "@example.test";

        tenantA = insertTenant("tenant-a-" + suffix);
        tenantB = insertTenant("tenant-b-" + suffix);

        Long ownerId = insertUser(ownerEmail);
        Long viewerId = insertUser(viewerEmail);

        ownerMemberId = insertMember(tenantA, ownerId);
        viewerMemberId = insertMember(tenantA, viewerId);

        roleSetup.ensureDefaults(tenantA);
        roleSetup.ensureDefaults(tenantB);
        roleSetup.grantLocalOwner(tenantA, ownerMemberId);

        Long viewerRole = jdbc.queryForObject("""
                SELECT id FROM mb_role
                WHERE tenant_id = ? AND role_code = 'VIEWER'
                """, Long.class, tenantA);

        jdbc.update("""
                INSERT INTO mb_member_role
                    (tenant_id, member_id, role_id)
                VALUES (?, ?, ?)
                """, tenantA, viewerMemberId, viewerRole);
    }

    private Long insertTenant(String code) {
        jdbc.update("""
                INSERT INTO mb_tenant(tenant_code, name)
                VALUES (?, ?)
                """, code, code);

        return jdbc.queryForObject("""
                SELECT id FROM mb_tenant WHERE tenant_code = ?
                """, Long.class, code);
    }

    private Long insertUser(String email) {
        jdbc.update("""
                INSERT INTO mb_user
                    (email, password_hash, display_name)
                VALUES (?, ?, ?)
                """, email, encoder.encode(PASSWORD), email);

        return jdbc.queryForObject("""
                SELECT id FROM mb_user WHERE email = ?
                """, Long.class, email);
    }

    private Long insertMember(Long tenantId, Long userId) {
        jdbc.update("""
                INSERT INTO mb_tenant_member(tenant_id, user_id)
                VALUES (?, ?)
                """, tenantId, userId);

        return jdbc.queryForObject("""
                SELECT id FROM mb_tenant_member
                WHERE tenant_id = ? AND user_id = ?
                """, Long.class, tenantId, userId);
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

    private String createKey(String token) throws Exception {
        return mvc.perform(post("/api/v1/tenant/api-keys")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-Id", tenantA)
                        .contentType("application/json")
                        .content("""
                                {"name":"test-ingest","expiresInDays":7}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void ownerShouldHaveManagementPermissions() throws Exception {
        String token = login(ownerEmail);

        mvc.perform(get("/api/v1/tenant/security/me")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-Id", tenantA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roles[0]").value("OWNER"))
                .andExpect(jsonPath("$.data.permissions")
                        .isArray());
    }

    @Test
    void viewerShouldNotCreateApiKey() throws Exception {
        String token = login(viewerEmail);

        mvc.perform(post("/api/v1/tenant/api-keys")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-Id", tenantA)
                        .contentType("application/json")
                        .content("""
                                {"name":"unauthorized","expiresInDays":7}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void apiKeyShouldWorkAndBeRevocable() throws Exception {
        String token = login(ownerEmail);
        String json = createKey(token);

        String rawKey = JsonPath.read(json, "$.data.apiKey");
        Number keyId = JsonPath.read(json, "$.data.id");

        mvc.perform(get("/api/v1/ingest/auth-check")
                        .header("X-API-Key", rawKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tenantId")
                        .value(tenantA));

        String listJson = mvc.perform(
                        get("/api/v1/tenant/api-keys")
                                .header("Authorization", "Bearer " + token)
                                .header("X-Tenant-Id", tenantA))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertFalse(listJson.contains(rawKey));

        mvc.perform(post(
                        "/api/v1/tenant/api-keys/{id}/revoke",
                        keyId.longValue())
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-Id", tenantA))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/ingest/auth-check")
                        .header("X-API-Key", rawKey))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void revokedJwtShouldBeRejected() throws Exception {
        String token = login(ownerEmail);

        mvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void ownerShouldBeAbleToChangeViewerToAdmin() throws Exception {
        String ownerToken = login(ownerEmail);

        mvc.perform(put(
                        "/api/v1/tenant/security/members/{id}/role",
                        viewerMemberId)
                        .header("Authorization",
                                "Bearer " + ownerToken)
                        .header("X-Tenant-Id", tenantA)
                        .contentType("application/json")
                        .content("""
                                {"roleCode":"ADMIN"}
                                """))
                .andExpect(status().isOk());

        String viewerToken = login(viewerEmail);

        mvc.perform(get("/api/v1/tenant/security/me")
                        .header("Authorization",
                                "Bearer " + viewerToken)
                        .header("X-Tenant-Id", tenantA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roles[0]").value("ADMIN"));

        createKey(viewerToken);
    }

    @Test
    void viewerShouldNotChangeRoles() throws Exception {
        String token = login(viewerEmail);

        mvc.perform(put(
                        "/api/v1/tenant/security/members/{id}/role",
                        ownerMemberId)
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-Id", tenantA)
                        .contentType("application/json")
                        .content("""
                                {"roleCode":"ADMIN"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void ownerShouldNotModifyOwnRole() throws Exception {
        String token = login(ownerEmail);

        mvc.perform(put(
                        "/api/v1/tenant/security/members/{id}/role",
                        ownerMemberId)
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-Id", tenantA)
                        .contentType("application/json")
                        .content("""
                                {"roleCode":"VIEWER"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void apiKeyShouldNotAccessAdminApi() throws Exception {
        String token = login(ownerEmail);
        String rawKey = JsonPath.read(
                createKey(token), "$.data.apiKey");

        mvc.perform(get("/api/v1/tenant/security/me")
                        .header("X-API-Key", rawKey)
                        .header("X-Tenant-Id", tenantA))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void jwtShouldNotReplaceIngestApiKey() throws Exception {
        String token = login(ownerEmail);

        mvc.perform(get("/api/v1/ingest/auth-check")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tenantSwitchShouldNotEscalatePrivileges() throws Exception {
        String token = login(ownerEmail);

        mvc.perform(get("/api/v1/tenant/security/me")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-Id", tenantB))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidApiKeyShouldBeRejected() throws Exception {
        mvc.perform(get("/api/v1/ingest/auth-check")
                        .header("X-API-Key", "invalid"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void auditShouldRecordKeyCreation() throws Exception {
        String token = login(ownerEmail);
        createKey(token);

        mvc.perform(get("/api/v1/tenant/security/audit")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-Id", tenantA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].action")
                        .value("API_KEY_CREATE"));

        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM mb_security_audit
                WHERE tenant_id = ? AND action = 'API_KEY_CREATE'
                """, Long.class, tenantA);

        assertEquals(1L, count);
    }

    @Test
    void auditLimitShouldAcceptDocumentedMaximumAndRejectLargerValues() throws Exception {
        String token = login(ownerEmail);

        mvc.perform(get("/api/v1/tenant/security/audit")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-Id", tenantA)
                        .param("limit", "100"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/tenant/security/audit")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-Id", tenantA)
                        .param("limit", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("limit 必须在 1 到 100 之间"));
    }
    @Test
    void viewerCannotInvokeAnyTenantBusinessWrite() throws Exception {
        String token = login(viewerEmail);
        String[] paths = {
                "/api/v1/tenant/subscriptions", "/api/v1/tenant/subscriptions/999/activate",
                "/api/v1/tenant/subscriptions/999/cancel", "/api/v1/tenant/subscriptions/999/renew",
                "/api/v1/tenant/subscriptions/999/plan-changes",
                "/api/v1/tenant/invoices/999/payments", "/api/v1/tenant/payments/999/refunds",
                "/api/v1/tenant/reconciliation/runs"};
        for (String path : paths) {
            mvc.perform(post(path).header("Authorization", "Bearer " + token)
                            .header("X-Tenant-Id", tenantA).header("Idempotency-Key", "deny-" + path)
                            .contentType("application/json").content("{\"planId\":999,\"amountMinor\":1}"))
                    .andExpect(status().isForbidden());
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM mb_subscription WHERE tenant_id = ?",
                Integer.class, tenantA));
    }

    @Test
    void businessPermissionsExistForManagersAndDowngradeTakesEffectImmediately() throws Exception {
        for (String permission : java.util.List.of("SUBSCRIPTION_MANAGE", "PAYMENT_MANAGE", "RECONCILIATION_RUN")) {
            assertEquals(2, jdbc.queryForObject("""
                    SELECT COUNT(*) FROM mb_role_permission p JOIN mb_role r ON r.id=p.role_id
                    WHERE r.tenant_id=? AND p.permission_code=? AND r.role_code IN ('OWNER','ADMIN')
                    """, Integer.class, tenantA, permission));
        }
        String token = login(viewerEmail);
        jdbc.update("DELETE FROM mb_member_role WHERE tenant_id=? AND member_id=?", tenantA, viewerMemberId);
        jdbc.update("""
                INSERT INTO mb_member_role SELECT ?, ?, id FROM mb_role WHERE tenant_id=? AND role_code='ADMIN'
                """, tenantA, viewerMemberId, tenantA);
        mvc.perform(post("/api/v1/tenant/reconciliation/runs").header("Authorization", "Bearer " + token)
                        .header("X-Tenant-Id", tenantA).header("Idempotency-Key", "allowed"))
                .andExpect(status().isOk());
        jdbc.update("DELETE FROM mb_member_role WHERE tenant_id=? AND member_id=?", tenantA, viewerMemberId);
        jdbc.update("""
                INSERT INTO mb_member_role SELECT ?, ?, id FROM mb_role WHERE tenant_id=? AND role_code='VIEWER'
                """, tenantA, viewerMemberId, tenantA);
        mvc.perform(post("/api/v1/tenant/reconciliation/runs").header("Authorization", "Bearer " + token)
                        .header("X-Tenant-Id", tenantA).header("Idempotency-Key", "allowed"))
                .andExpect(status().isForbidden());
    }
}
