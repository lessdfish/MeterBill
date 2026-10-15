
package com.meterbill.identity.service;

import com.meterbill.common.exception.BusinessException;
import com.meterbill.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ApiKeyService {

    private final JdbcTemplate jdbc;
    private final TenantRoleService roles;
    private final SecurityAuditService audit;

    private static final SecureRandom RANDOM = new SecureRandom();

    @Transactional
    public CreatedKey create(
            Long tenantId, Long userId,
            String name, int expiresInDays) {

        roles.require(tenantId, userId, "API_KEY_MANAGE");

        if (expiresInDays < 1 || expiresInDays > 90) {
            throw new BusinessException(
                    ErrorCode.INVALID_ARGUMENT,
                    "密钥有效期必须在 1 到 90 天之间");
        }

        Long memberId = jdbc.queryForObject("""
                SELECT id FROM mb_tenant_member
                WHERE tenant_id = ? AND user_id = ?
                  AND status = 'ACTIVE'
                """, Long.class, tenantId, userId);

        byte[] prefixBytes = new byte[8];
        byte[] secretBytes = new byte[32];

        RANDOM.nextBytes(prefixBytes);
        RANDOM.nextBytes(secretBytes);

        String prefix = HexFormat.of().formatHex(prefixBytes);
        String secret = Base64.getUrlEncoder()
                .withoutPadding().encodeToString(secretBytes);

        String rawKey = "mbk_" + prefix + "_" + secret;

        LocalDateTime expiresAt = LocalDateTime.ofInstant(
                Instant.now().plusSeconds(expiresInDays * 86400L),
                ZoneOffset.UTC
        );

        jdbc.update("""
                INSERT INTO mb_api_key
                    (tenant_id, created_by_member_id, name,
                     key_prefix, key_hash, expires_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                tenantId, memberId, name,
                prefix, hash(rawKey), expiresAt);

        Long keyId = jdbc.queryForObject("""
                SELECT id FROM mb_api_key WHERE key_prefix = ?
                """, Long.class, prefix);

        audit.record(
                tenantId, userId,
                "API_KEY_CREATE", "API_KEY", keyId.toString());

        return new CreatedKey(keyId, name, rawKey, expiresAt);
    }

    @Transactional(readOnly = true)
    public List<KeyInfo> list(Long tenantId, Long userId) {
        roles.require(tenantId, userId, "API_KEY_MANAGE");

        return jdbc.query("""
                SELECT id, name, key_prefix, status, expires_at
                FROM mb_api_key
                WHERE tenant_id = ?
                ORDER BY id DESC
                """,
                (rs, row) -> new KeyInfo(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("key_prefix"),
                        rs.getString("status"),
                        rs.getObject("expires_at", LocalDateTime.class)
                ), tenantId);
    }

    @Transactional
    public void revoke(Long tenantId, Long userId, Long keyId) {
        roles.require(tenantId, userId, "API_KEY_MANAGE");

        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM mb_api_key
                WHERE tenant_id = ? AND id = ?
                """, Long.class, tenantId, keyId);

        if (count == null || count == 0) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        int changed = jdbc.update("""
                UPDATE mb_api_key
                SET status = 'REVOKED',
                    revoked_at = UTC_TIMESTAMP(6)
                WHERE tenant_id = ?
                  AND id = ?
                  AND status = 'ACTIVE'
                """, tenantId, keyId);

        if (changed > 0) {
            audit.record(
                    tenantId, userId,
                    "API_KEY_REVOKE", "API_KEY", keyId.toString());
        }
    }

    @Transactional(readOnly = true)
    public AuthenticatedKey authenticate(String rawKey) {
        if (rawKey == null
                || !rawKey.matches(
                "mbk_[0-9a-f]{16}_[A-Za-z0-9_-]{43}")) {
            return null;
        }

        String prefix = rawKey.substring(4, 20);

        List<KeyRow> candidates = jdbc.query("""
                SELECT k.id, k.tenant_id, k.key_hash
                FROM mb_api_key k
                JOIN mb_tenant t ON t.id = k.tenant_id
                WHERE k.key_prefix = ?
                  AND k.status = 'ACTIVE'
                  AND k.expires_at > UTC_TIMESTAMP(6)
                  AND t.status = 'ACTIVE'
                """,
                (rs, row) -> new KeyRow(
                        rs.getLong("id"),
                        rs.getLong("tenant_id"),
                        rs.getBytes("key_hash")
                ), prefix);

        if (candidates.isEmpty()) {
            return null;
        }

        KeyRow key = candidates.getFirst();

        if (!MessageDigest.isEqual(
                key.hash(), hash(rawKey))) {
            return null;
        }

        return new AuthenticatedKey(key.id(), key.tenantId());
    }

    private byte[] hash(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private record KeyRow(Long id, Long tenantId, byte[] hash) {
    }

    public record AuthenticatedKey(Long keyId, Long tenantId) {
    }

    public record CreatedKey(
            Long id, String name, String apiKey,
            LocalDateTime expiresAt) {
    }

    public record KeyInfo(
            Long id, String name, String prefix,
            String status, LocalDateTime expiresAt) {
    }
}
