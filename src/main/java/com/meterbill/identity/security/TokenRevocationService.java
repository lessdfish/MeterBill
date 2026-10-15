
package com.meterbill.identity.security;

import com.meterbill.common.exception.BusinessException;
import com.meterbill.common.exception.ErrorCode;
import com.meterbill.identity.service.SecurityAuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
public class TokenRevocationService {

    private final JdbcTemplate jdbc;
    private final SecurityAuditService audit;

    @Transactional(readOnly = true)
    public boolean isRevoked(String jti) {
        if (jti == null || jti.isBlank()) {
            return true;
        }

        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM mb_revoked_jwt
                WHERE jti = ?
                """, Long.class, jti);

        return count != null && count > 0;
    }

    @Transactional
    public void logout(Jwt jwt) {
        if (jwt.getId() == null
                || jwt.getExpiresAt() == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        Long userId = Long.valueOf(jwt.getSubject());

        LocalDateTime expiresAt = LocalDateTime.ofInstant(
                jwt.getExpiresAt(), ZoneOffset.UTC);

        jdbc.update("""
                INSERT IGNORE INTO mb_revoked_jwt
                    (jti, user_id, expires_at)
                VALUES (?, ?, ?)
                """, jwt.getId(), userId, expiresAt);

        audit.record(
                null, userId, "JWT_LOGOUT",
                "JWT", jwt.getId());
    }
}
