
package com.meterbill.identity;

import com.meterbill.identity.persistence.entity.Tenant;
import com.meterbill.identity.persistence.mapper.TenantMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.springframework.dao.DataAccessException;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles("test")
@Testcontainers
@SpringBootTest
class IdentitySchemaIntegrationTest {

    @Container
    static final MySQLContainer mysql =
            new MySQLContainer("mysql:8.4")
                    .withDatabaseName("meterbill_test")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TenantMapper tenantMapper;

    @Test
    void shouldCreateIdentityTables() {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name IN (
                      'mb_tenant',
                      'mb_user',
                      'mb_tenant_member'
                  )
                """, Integer.class);

        assertEquals(3, count);
    }

    @Test
    @Transactional
    void tenantMapperShouldInsertAndSelect() {
        Tenant tenant = new Tenant();
        tenant.setTenantCode("acme");
        tenant.setName("Acme Corporation");
        tenant.setStatus("ACTIVE");

        assertEquals(1, tenantMapper.insert(tenant));
        assertNotNull(tenant.getId());

        Tenant saved = tenantMapper.selectById(tenant.getId());
        assertEquals("Acme Corporation", saved.getName());
    }

    @Test
    @Transactional
    void duplicateTenantCodeShouldFail() {
        jdbc.update("""
                INSERT INTO mb_tenant(tenant_code, name)
                VALUES ('duplicate-tenant', 'First')
                """);

        assertThrows(DataIntegrityViolationException.class, () ->
                jdbc.update("""
                        INSERT INTO mb_tenant(tenant_code, name)
                        VALUES ('duplicate-tenant', 'Second')
                        """));
    }

    @Test
    @Transactional
    void memberWithUnknownTenantShouldFail() {
        jdbc.update("""
                INSERT INTO mb_user(email, password_hash, display_name)
                VALUES ('test@example.com', 'test-only', 'Test User')
                """);

        Long userId = jdbc.queryForObject(
                "SELECT id FROM mb_user WHERE email = 'test@example.com'",
                Long.class
        );

        assertThrows(DataIntegrityViolationException.class, () ->
                jdbc.update("""
                        INSERT INTO mb_tenant_member(tenant_id, user_id)
                        VALUES (?, ?)
                        """, Long.MAX_VALUE, userId));
    }

    @Test
    @Transactional
    void invalidTenantStatusShouldFail() {
        DataAccessException ex = assertThrows(
                DataAccessException.class,
                () -> jdbc.update("""
                    INSERT INTO mb_tenant(tenant_code, name, status)
                    VALUES ('invalid-tenant', 'Invalid', 'UNKNOWN')
                    """)
        );

        SQLException sqlEx = assertInstanceOf(
                SQLException.class,
                ex.getMostSpecificCause()
        );

        assertEquals(3819, sqlEx.getErrorCode());
    }
}
