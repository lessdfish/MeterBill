package com.meterbill.identity.bootstrap;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.meterbill.identity.persistence.entity.Tenant;
import com.meterbill.identity.persistence.entity.TenantMember;
import com.meterbill.identity.persistence.entity.User;
import com.meterbill.identity.persistence.mapper.TenantMapper;
import com.meterbill.identity.persistence.mapper.TenantMemberMapper;
import com.meterbill.identity.persistence.mapper.UserMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Locale;

@Configuration
@Profile({"local", "demo", "perf"})
public class LocalBootstrapConfig {

    @Bean
    @Order(0)
    @ConditionalOnProperty(
            prefix = "meterbill.bootstrap",
            name = "enabled",
            havingValue = "true"
    )
    CommandLineRunner initializeLocalAccount(
            TenantMapper tenantMapper,
            UserMapper userMapper,
            TenantMemberMapper memberMapper,
            PasswordEncoder encoder,
            JdbcTemplate jdbc,
            TransactionTemplate transaction,
            TenantRoleSetup roleSetup,
            @Value("${meterbill.bootstrap.email}") String email,
            @Value("${meterbill.bootstrap.password}") String password,
            @Value("${meterbill.bootstrap.tenant-code}") String tenantCode,
            @Value("${meterbill.bootstrap.tenant-name:ACME Demo}") String tenantName) {

        return args -> {

            if (email.isBlank()
                    || password.length() < 12
                    || tenantCode.isBlank()) {

                throw new IllegalStateException(
                        "Invalid local bootstrap credentials"
                );
            }


            transaction.executeWithoutResult(status -> {

                jdbc.update("""
                        INSERT IGNORE INTO mb_tenant(tenant_code, name, status)
                        VALUES (?, ?, 'ACTIVE')
                        """, tenantCode, tenantName);
                Tenant tenant = tenantMapper.selectOne(
                        new LambdaQueryWrapper<Tenant>()
                                .eq(Tenant::getTenantCode, tenantCode)
                );


                String normalizedEmail =
                        email.trim().toLowerCase(Locale.ROOT);


                jdbc.update("""
                        INSERT IGNORE INTO mb_user(email, password_hash, display_name, status)
                        VALUES (?, ?, 'Local Administrator', 'ACTIVE')
                        """, normalizedEmail, encoder.encode(password));
                User user = userMapper.selectOne(
                        new LambdaQueryWrapper<User>()
                                .eq(User::getEmail, normalizedEmail)
                );
                if (!encoder.matches(
                        password,
                        user.getPasswordHash())) {

                    throw new IllegalStateException(
                            "Bootstrap user already exists with a different password"
                    );
                }


                jdbc.update("""
                        INSERT IGNORE INTO mb_tenant_member(tenant_id, user_id, status)
                        VALUES (?, ?, 'ACTIVE')
                        """, tenant.getId(), user.getId());
                TenantMember member = memberMapper.selectOne(
                        new LambdaQueryWrapper<TenantMember>()
                                .eq(
                                        TenantMember::getTenantId,
                                        tenant.getId()
                                )
                                .eq(
                                        TenantMember::getUserId,
                                        user.getId()
                                )
                );


                /*
                 * 无论 member 是否第一次创建，
                 * 都执行 OWNER 初始化。
                 *
                 * grantLocalOwner 内部负责幂等。
                 */
                roleSetup.grantLocalOwner(
                        tenant.getId(),
                        member.getId()
                );
            });
        };
    }
}
