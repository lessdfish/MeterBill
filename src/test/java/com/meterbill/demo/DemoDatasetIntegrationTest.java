package com.meterbill.demo;

import com.meterbill.operations.service.ReconciliationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@SpringBootTest
@ActiveProfiles({"test", "demo"})
class DemoDatasetIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("meterbill_demo")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ReconciliationService reconciliation;

    @Test
    void fixedDemoDatasetCoversMainAndExceptionalScenarios() {
        assertEquals("云智 AI 开放平台", jdbc.queryForObject(
                "SELECT name FROM mb_tenant WHERE id=900001", String.class));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM mb_plan WHERE product_id=900001", Integer.class));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM mb_usage_meter WHERE product_id=900001", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM mb_subscription WHERE tenant_id=900001 AND status='ACTIVE'", Integer.class));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM mb_usage_event WHERE tenant_id=900001 AND status='PROCESSED'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM mb_payment WHERE tenant_id=900001 AND status='UNKNOWN'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM mb_payment WHERE tenant_id=900001 AND status='REVOKED'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM mb_refund WHERE tenant_id=900001 AND status='SUCCEEDED'", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='mb_payment_reversal'",
                Integer.class));
        assertTrue(reconciliation.run(900001L, "demo-dataset-verification", null).differences().isEmpty());
    }
}
