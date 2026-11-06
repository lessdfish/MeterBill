package com.meterbill.identity.bootstrap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

@Configuration
@Profile("perf")
public class PerformanceTenantAdminConfig {

    @Bean
    @Order(10)
    @ConditionalOnProperty(prefix = "meterbill.performance-data", name = "enabled", havingValue = "true")
    CommandLineRunner grantPerformanceTenantAccess(
            JdbcTemplate jdbc,
            TenantRoleSetup roleSetup,
            @Value("${meterbill.bootstrap.email}") String email) {
        return args -> {
            Long userId = jdbc.queryForObject(
                    "SELECT id FROM mb_user WHERE email = ?", Long.class, email.trim().toLowerCase());
            jdbc.update("INSERT IGNORE INTO mb_platform_admin(user_id) VALUES (?)", userId);
            List<Long> tenantIds = jdbc.queryForList(
                    "SELECT id FROM mb_tenant WHERE tenant_code LIKE 'PERF_TENANT_%' ORDER BY id",
                    Long.class);
            for (Long tenantId : tenantIds) {
                jdbc.update("""
                        INSERT IGNORE INTO mb_tenant_member(tenant_id, user_id, status)
                        VALUES (?, ?, 'ACTIVE')
                        """, tenantId, userId);
                Long memberId = jdbc.queryForObject("""
                        SELECT id FROM mb_tenant_member WHERE tenant_id = ? AND user_id = ?
                        """, Long.class, tenantId, userId);
                roleSetup.grantLocalOwner(tenantId, memberId);
            }
        };
    }
}
