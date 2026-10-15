package com.meterbill.identity.service;

import com.meterbill.common.exception.BusinessException;
import com.meterbill.common.exception.ErrorCode;
import com.meterbill.identity.security.TenantContext;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.ZoneId;

@Service
@RequiredArgsConstructor
public class TenantSettingsService {
    private final JdbcTemplate jdbc;
    private final TenantRoleService roles;
    private final SecurityAuditService audit;

    @Transactional(readOnly = true)
    public Settings get() {
        Long tenantId = TenantContext.requiredTenantId();
        return jdbc.queryForObject("SELECT tenant_code,name,billing_zone_id,version FROM mb_tenant WHERE id=?",
                (rs, row) -> new Settings(rs.getString("tenant_code"), rs.getString("name"),
                        rs.getString("billing_zone_id"), rs.getInt("version")), tenantId);
    }

    @Transactional
    public Settings update(Long userId, Update request) {
        Long tenantId = TenantContext.requiredTenantId();
        roles.require(tenantId, userId, "MEMBER_ROLE_MANAGE");
        try {
            ZoneId.of(request.billingZoneId());
        } catch (DateTimeException ex) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "无效的 IANA 时区");
        }
        int updated = jdbc.update("""
                UPDATE mb_tenant SET name=?,billing_zone_id=?,version=version+1
                WHERE id=? AND version=?
                """, request.name().trim(), request.billingZoneId(), tenantId, request.version());
        if (updated == 0) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "设置已被其他操作修改，请刷新后重试");
        }
        audit.record(tenantId, userId, "TENANT_SETTINGS_UPDATE", "TENANT", tenantId.toString());
        return get();
    }

    public record Settings(String tenantCode, String name, String billingZoneId, int version) { }
    public record Update(@NotBlank @Size(max=128) String name,
                         @NotBlank @Size(max=64) String billingZoneId,
                         @NotNull Integer version) { }
}
