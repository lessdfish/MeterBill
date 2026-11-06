package com.meterbill.performance;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ActiveProfiles({"test", "perf"})
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "meterbill.bootstrap.enabled=true",
        "meterbill.bootstrap.email=perf-admin@meterbill.local",
        "meterbill.bootstrap.password=MeterBill-Perf-2026!",
        "meterbill.bootstrap.tenant-code=PERF_ADMIN",
        "meterbill.performance-data.enabled=true"
})
class PerformanceDatasetIntegrationTest {

    @Container
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4")
            .withDatabaseName("meterbill_perf")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void createsIsolatedMultiTenantPerformanceDataset() {
        assertEquals(100, count("SELECT COUNT(*) FROM mb_tenant WHERE tenant_code LIKE 'PERF_TENANT_%'"));
        assertEquals(500, count("SELECT COUNT(*) FROM mb_subscription WHERE id BETWEEN 920001 AND 920500"));
        assertEquals(0, count("""
                SELECT COUNT(*) FROM (
                    SELECT tenant_id FROM mb_subscription
                    WHERE id BETWEEN 920001 AND 920500
                    GROUP BY tenant_id HAVING COUNT(*) <> 5
                ) invalid_counts
                """));
        assertEquals(100, count("""
                SELECT COUNT(*) FROM mb_tenant_member m
                JOIN mb_user u ON u.id=m.user_id
                JOIN mb_tenant t ON t.id=m.tenant_id
                WHERE u.email='perf-admin@meterbill.local'
                  AND t.tenant_code LIKE 'PERF_TENANT_%'
                  AND m.status='ACTIVE'
                """));
        assertEquals(1, count("""
                SELECT COUNT(*) FROM mb_platform_admin a
                JOIN mb_user u ON u.id=a.user_id
                WHERE u.email='perf-admin@meterbill.local'
                """));
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }
}
