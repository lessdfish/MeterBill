
package com.meterbill.identity.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.meterbill.common.exception.BusinessException;
import com.meterbill.common.exception.ErrorCode;
import com.meterbill.identity.persistence.entity.Tenant;
import com.meterbill.identity.persistence.entity.TenantMember;
import com.meterbill.identity.persistence.entity.User;
import com.meterbill.identity.persistence.mapper.TenantMapper;
import com.meterbill.identity.persistence.mapper.TenantMemberMapper;
import com.meterbill.identity.persistence.mapper.UserMapper;
import com.meterbill.identity.security.TokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final TenantMapper tenantMapper;
    private final TenantMemberMapper memberMapper;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final SecurityAuditService audit;
    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public LoginResult login(String email, String password) {
        String normalized = email.trim().toLowerCase(Locale.ROOT);

        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getEmail, normalized)
        );

        if (user == null
                || !"ACTIVE".equals(user.getStatus())
                || !passwordEncoder.matches(password, user.getPasswordHash())) {

            audit.recordLogin(user == null ? null : user.getId() ,false);

            throw new BusinessException(
                    ErrorCode.INVALID_CREDENTIALS);
        }

        TokenService.TokenResult token = tokenService.issue(user.getId());

        audit.recordLogin(user.getId(),true);

        return new LoginResult(
                "Bearer",
                token.accessToken(),
                token.expiresIn()
        );
    }

    @Transactional(readOnly = true)
    public UserProfile profile(Long userId) {
        User user = userMapper.selectById(userId);

        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        boolean platformAdmin = jdbc.queryForObject("""
                SELECT COUNT(*) > 0 FROM mb_platform_admin WHERE user_id = ?
                """, Boolean.class, userId);
        List<TenantInfo> tenants;
        if (platformAdmin) {
            tenants = tenantMapper.selectList(
                    new LambdaQueryWrapper<Tenant>()
                            .eq(Tenant::getStatus, "ACTIVE")
                            .orderByAsc(Tenant::getId)
            ).stream().map(tenant -> new TenantInfo(
                    tenant.getId(), tenant.getTenantCode(), tenant.getName())).toList();
        } else {
            tenants = memberMapper.selectList(
                    new LambdaQueryWrapper<TenantMember>()
                            .eq(TenantMember::getUserId, userId)
                            .eq(TenantMember::getStatus, "ACTIVE")
            ).stream().map(member -> {
                Tenant tenant = tenantMapper.selectById(member.getTenantId());
                if (tenant == null || !"ACTIVE".equals(tenant.getStatus())) {
                    return null;
                }
                return new TenantInfo(
                        tenant.getId(), tenant.getTenantCode(), tenant.getName());
            }).filter(item -> item != null).toList();
        }

        return new UserProfile(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                platformAdmin,
                tenants
        );
    }

    public record LoginResult(
            String tokenType,
            String accessToken,
            long expiresIn
    ) {
    }

    public record UserProfile(
            Long userId,
            String email,
            String displayName,
            boolean platformAdmin,
            List<TenantInfo> tenants
    ) {
    }

    public record TenantInfo(
            Long tenantId,
            String tenantCode,
            String tenantName
    ) {
    }
}
