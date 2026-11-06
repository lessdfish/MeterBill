package com.meterbill.account.service;

import com.meterbill.account.api.AccountQueryApi;
import com.meterbill.account.web.dto.CreateAccountRequest;
import com.meterbill.account.web.vo.AccountVO;
import com.meterbill.common.event.DomainEventService;
import com.meterbill.common.exception.BusinessException;
import com.meterbill.common.exception.ErrorCode;
import com.meterbill.identity.service.SecurityAuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AccountService implements AccountQueryApi {

    private final JdbcTemplate jdbc;
    private final SecurityAuditService audit;
    private final DomainEventService events;

    @Transactional
    public AccountVO create(Long tenantId, Long actorId, CreateAccountRequest request) {
        String zone = request.timeZoneId();
        if (zone == null || zone.isBlank()) {
            zone = jdbc.queryForObject("SELECT billing_zone_id FROM mb_tenant WHERE id = ?", String.class, tenantId);
        }
        try {
            ZoneId.of(zone);
        } catch (RuntimeException ex) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "客户时区不合法");
        }
        try {
            jdbc.update("""
                    INSERT INTO mb_customer_account
                        (tenant_id, external_key, name, email, currency, time_zone_id, status, is_default)
                    VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', FALSE)
                    """, tenantId, request.externalKey().trim(), request.name().trim(),
                    blankToNull(request.email()), request.currency(), zone);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "客户外部标识已存在");
        }
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        events.append(tenantId, id, "ACCOUNT", id, 0L, "ACCOUNT_CREATED",
                "account:" + id + ":created", null,
                Map.of("externalKey", request.externalKey().trim(), "currency", request.currency()));
        audit.record(tenantId, actorId, "ACCOUNT_CREATE", "ACCOUNT", id.toString());
        return get(tenantId, id);
    }

    @Transactional(readOnly = true)
    public AccountVO get(Long tenantId, Long accountId) {
        List<AccountVO> rows = jdbc.query("""
                SELECT id, tenant_id, external_key, name, email, currency, time_zone_id,
                       status, is_default, version, created_at
                FROM mb_customer_account WHERE tenant_id = ? AND id = ?
                """, (rs, n) -> view(rs), tenantId, accountId);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "客户账户不存在");
        }
        return rows.getFirst();
    }

    @Transactional
    public List<AccountVO> list(Long tenantId) {
        requireDefault(tenantId);
        return jdbc.query("""
                SELECT id, tenant_id, external_key, name, email, currency, time_zone_id,
                       status, is_default, version, created_at
                FROM mb_customer_account WHERE tenant_id = ? ORDER BY is_default DESC, id
                """, (rs, n) -> view(rs), tenantId);
    }

    @Override
    @Transactional(readOnly = true)
    public AccountRef require(Long tenantId, Long accountId) {
        List<AccountRef> rows = jdbc.query("""
                SELECT id, tenant_id, currency, time_zone_id, status
                FROM mb_customer_account WHERE tenant_id = ? AND id = ?
                """, (rs, n) -> new AccountRef(rs.getLong(1), rs.getLong(2),
                rs.getString(3), rs.getString(4), rs.getString(5)), tenantId, accountId);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "客户账户不存在");
        }
        if (!"ACTIVE".equals(rows.getFirst().status())) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "客户账户当前不可用");
        }
        return rows.getFirst();
    }

    @Override
    @Transactional
    public AccountRef requireDefault(Long tenantId) {
        jdbc.update("""
                INSERT IGNORE INTO mb_customer_account
                    (tenant_id, external_key, name, currency, time_zone_id, status, is_default)
                SELECT id, CONCAT('default-', tenant_code), CONCAT(name, ' 默认客户'),
                       'CNY', billing_zone_id, 'ACTIVE', TRUE
                FROM mb_tenant WHERE id = ?
                """, tenantId);
        List<Long> ids = jdbc.query("""
                SELECT id FROM mb_customer_account
                WHERE tenant_id = ? AND is_default = TRUE AND status = 'ACTIVE'
                ORDER BY id LIMIT 1
                """, (rs, n) -> rs.getLong(1), tenantId);
        if (ids.isEmpty()) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "租户缺少默认客户账户");
        }
        return require(tenantId, ids.getFirst());
    }

    private AccountVO view(java.sql.ResultSet rs) throws java.sql.SQLException {
        LocalDateTime created = rs.getObject("created_at", LocalDateTime.class);
        return new AccountVO(rs.getLong("id"), rs.getLong("tenant_id"),
                rs.getString("external_key"), rs.getString("name"), rs.getString("email"),
                rs.getString("currency"), rs.getString("time_zone_id"), rs.getString("status"),
                rs.getBoolean("is_default"), rs.getInt("version"), created.toInstant(ZoneOffset.UTC));
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
