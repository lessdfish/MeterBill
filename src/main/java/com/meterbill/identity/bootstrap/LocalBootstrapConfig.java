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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Locale;

@Configuration
@Profile("local")
public class LocalBootstrapConfig {

    @Bean
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
            TransactionTemplate transaction,
            TenantRoleSetup roleSetup,
            @Value("${meterbill.bootstrap.email}") String email,
            @Value("${meterbill.bootstrap.password}") String password,
            @Value("${meterbill.bootstrap.tenant-code}") String tenantCode) {

        return args -> {

            if (email.isBlank()
                    || password.length() < 12
                    || tenantCode.isBlank()) {

                throw new IllegalStateException(
                        "Invalid local bootstrap credentials"
                );
            }


            transaction.executeWithoutResult(status -> {

                Tenant tenant = tenantMapper.selectOne(
                        new LambdaQueryWrapper<Tenant>()
                                .eq(Tenant::getTenantCode, tenantCode)
                );


                if (tenant == null) {

                    tenant = new Tenant();
                    tenant.setTenantCode(tenantCode);
                    tenant.setName("ACME Demo");
                    tenant.setStatus("ACTIVE");

                    tenantMapper.insert(tenant);
                }


                String normalizedEmail =
                        email.trim().toLowerCase(Locale.ROOT);


                User user = userMapper.selectOne(
                        new LambdaQueryWrapper<User>()
                                .eq(User::getEmail, normalizedEmail)
                );


                if (user == null) {

                    user = new User();

                    user.setEmail(normalizedEmail);
                    user.setDisplayName("Local Administrator");
                    user.setPasswordHash(
                            encoder.encode(password)
                    );
                    user.setStatus("ACTIVE");

                    userMapper.insert(user);

                } else if (!encoder.matches(
                        password,
                        user.getPasswordHash())) {

                    throw new IllegalStateException(
                            "Bootstrap user already exists with a different password"
                    );
                }


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


                if (member == null) {

                    member = new TenantMember();

                    member.setTenantId(
                            tenant.getId()
                    );

                    member.setUserId(
                            user.getId()
                    );

                    member.setStatus(
                            "ACTIVE"
                    );

                    memberMapper.insert(member);
                }


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