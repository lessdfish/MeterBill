package com.meterbill.identity.bootstrap;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * ClassName:TenantRoleSetup
 * Package:com.meterbill.identity.bootstrap
 * Description:
 *
 * @Author:lyp
 * @Create:2026/10/11 - 10:56
 * @Version: v1.0
 *
 */
@Component
@RequiredArgsConstructor
public class TenantRoleSetup {

    private final JdbcTemplate jdbc;

    private static final Map<String, List<String>> ROLE_PERMISSIONS =
            Map.of(
                    "OWNER", List.of(
                            "TENANT_READ",
                            "MEMBER_ROLE_MANAGE",
                            "API_KEY_MANAGE",
                            "AUDIT_READ", "SUBSCRIPTION_MANAGE", "PAYMENT_MANAGE", "RECONCILIATION_RUN"),
                    "ADMIN", List.of(
                            "TENANT_READ",
                            "MEMBER_ROLE_MANAGE",
                            "API_KEY_MANAGE",
                            "AUDIT_READ", "SUBSCRIPTION_MANAGE", "PAYMENT_MANAGE", "RECONCILIATION_RUN"),
                    "VIEWER", List.of("TENANT_READ")
            );

    public void ensureDefaults(Long tenantId){
        for (var entry : ROLE_PERMISSIONS.entrySet()) {
            String roleCode = entry.getKey();

            jdbc.update("""
                    INSERT INTO mb_role
                        (tenant_id, role_code, name)
                    VALUES (?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                        name = VALUES(name)
                    """, tenantId, roleCode, roleCode);

            Long roleId = jdbc.queryForObject("""
                    SELECT id FROM mb_role
                    WHERE tenant_id = ? AND role_code = ?
                    """, Long.class, tenantId, roleCode);

            for (String permission : entry.getValue()) {
                jdbc.update("""
                        INSERT IGNORE INTO mb_role_permission
                            (role_id, permission_code)
                        VALUES (?, ?)
                        """, roleId, permission);
            }
        }
    }

    public void grantLocalOwner(Long tenantId, Long memberId) {
        ensureDefaults(tenantId);

        Long roleId = jdbc.queryForObject("""
                SELECT id FROM mb_role
                WHERE tenant_id = ? AND role_code = 'OWNER'
                """, Long.class, tenantId);

        jdbc.update("""
                INSERT INTO mb_member_role
                    (tenant_id, member_id, role_id)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    role_id = VALUES(role_id)
                """, tenantId, memberId, roleId);
    }


}
