package com.meterbill.identity.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.meterbill.identity.persistence.entity.Tenant;
import com.meterbill.identity.persistence.entity.TenantMember;
import com.meterbill.identity.persistence.mapper.TenantMapper;
import com.meterbill.identity.persistence.mapper.TenantMemberMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ClassName:TenantAccessService
 * Package:com.meterbill.identity.security
 * Description:
 *
 * @Author:lyp
 * @Create:2026/10/10 - 18:19
 * @Version: v1.0
 *
 */
@Service
@RequiredArgsConstructor
public class TenantAccessService {

    private final TenantMapper tenantMapper;
    private final TenantMemberMapper memberMapper;
    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public boolean canAccess(Long userId, Long tenantId) {
        Tenant tenant = tenantMapper.selectById(tenantId);

        if (tenant == null || !"ACTIVE".equals(tenant.getStatus())) {
            return false;
        }

        Long platformAdmin = jdbc.queryForObject("""
                SELECT COUNT(*) FROM mb_platform_admin WHERE user_id = ?
                """, Long.class, userId);
        if (platformAdmin != null && platformAdmin > 0) {
            return true;
        }

        Long count = memberMapper.selectCount(
                new LambdaQueryWrapper<TenantMember>()
                        .eq(TenantMember::getUserId, userId)
                        .eq(TenantMember::getTenantId, tenantId)
                        .eq(TenantMember::getStatus, "ACTIVE")
        );

        return count > 0;
    }
}
