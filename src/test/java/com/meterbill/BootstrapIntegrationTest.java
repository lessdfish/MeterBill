package com.meterbill;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ClassName:BootstrapIntegrationTest
 * Package:com.meterbill
 * Description:
 *
 * @Author:lyp
 * @Create:2026/10/10 - 13:41
 * @Version: v1.0
 *
 */
@ActiveProfiles("test")
@Testcontainers
@SpringBootTest
public class BootstrapIntegrationTest {
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
    private JdbcTemplate jdbcTemplate;

    @Test
    void flywayMigrationShouldSucceed() {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM flyway_schema_history
                WHERE version = '1' AND success = 1
                """,
                Integer.class
        );

        assertEquals(1, count);
    }
}
