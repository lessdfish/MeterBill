package com.meterbill.identity.bootstrap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Configuration
@Profile("demo")
public class DemoPlatformAdminConfig {

    @Bean
    @Order(10)
    @ConditionalOnProperty(prefix = "meterbill.demo-data", name = "enabled", havingValue = "true")
    CommandLineRunner grantDemoPlatformAdmin(
            JdbcTemplate jdbc,
            @Value("${meterbill.bootstrap.email}") String email) {
        return args -> grant(jdbc, email);
    }

    @Transactional
    void grant(JdbcTemplate jdbc, String email) {
        Long userId = jdbc.queryForObject(
                "SELECT id FROM mb_user WHERE email = ?",
                Long.class,
                email.trim().toLowerCase());
        jdbc.update("INSERT IGNORE INTO mb_platform_admin(user_id) VALUES (?)", userId);
    }
}
