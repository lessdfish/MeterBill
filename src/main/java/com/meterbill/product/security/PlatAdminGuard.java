package com.meterbill.product.security;

import com.meterbill.common.exception.BusinessException;
import com.meterbill.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * ClassName:PlatAdminGuard
 * Package:com.meterbill.product.security
 * Description:
 *
 * @Author:lyp
 * @Create:2026/10/11 - 13:22
 * @Version: v1.0
 *
 */
@Component
@RequiredArgsConstructor
public class PlatAdminGuard {

    private final JdbcTemplate jdbc;

    public void requireAdmin(Long userId) {
        if (!isAdmin(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    public boolean isAdmin(Long userId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM mb_platform_admin
                WHERE user_id = ?
                """, Long.class, userId);
        return count != null && count > 0;
    }
}
