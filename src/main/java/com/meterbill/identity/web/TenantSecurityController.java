
package com.meterbill.identity.web;

import com.meterbill.common.api.ApiResponse;
import com.meterbill.common.exception.BusinessException;
import com.meterbill.common.exception.ErrorCode;
import com.meterbill.identity.security.TenantContext;
import com.meterbill.identity.service.SecurityAuditService;
import com.meterbill.identity.service.TenantRoleService;
import com.meterbill.product.security.PlatAdminGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "成员、角色与审计", description = "维护当前租户成员角色并查询操作审计")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/tenant/security")
public class TenantSecurityController {

    private final TenantRoleService roles;
    private final SecurityAuditService audit;
    private final PlatAdminGuard platformAdmins;

    @Operation(summary = "查询我的租户角色与权限")
    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> permissions(
            @AuthenticationPrincipal Jwt jwt) {

        Long tenantId = TenantContext.requiredTenantId();
        Long userId = Long.valueOf(jwt.getSubject());

        return ApiResponse.success(Map.of(
                "tenantId", tenantId,
                "roles", roles.roles(tenantId, userId),
                "permissions", roles.permissions(tenantId, userId)
        ));
    }

    @Operation(summary = "修改成员角色")
    @PutMapping("/members/{memberId}/role")
    public ApiResponse<Void> changeRole(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long memberId,
            @Valid @RequestBody ChangeRoleRequest request) {

        roles.changeRole(
                TenantContext.requiredTenantId(),
                Long.valueOf(jwt.getSubject()),
                memberId,
                request.roleCode(),
                platformAdmins.isAdmin(Long.valueOf(jwt.getSubject()))
        );

        return ApiResponse.success(null);
    }

    @Operation(summary = "添加已有账号为租户成员")
    @PostMapping("/members")
    public ApiResponse<Long> addMember(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody AddMemberRequest request) {
        Long actorId = Long.valueOf(jwt.getSubject());
        return ApiResponse.success(roles.addExistingMember(
                TenantContext.requiredTenantId(), actorId, request.email(), request.roleCode(),
                platformAdmins.isAdmin(actorId)));
    }

    @Operation(summary = "停用租户成员")
    @PostMapping("/members/{memberId}/disable")
    public ApiResponse<Void> disableMember(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long memberId) {
        Long actorId = Long.valueOf(jwt.getSubject());
        roles.disableMember(TenantContext.requiredTenantId(), actorId, memberId,
                platformAdmins.isAdmin(actorId));
        return ApiResponse.success(null);
    }

    @Operation(summary = "查询租户操作审计")
    @GetMapping("/audit")
    public ApiResponse<?> audit(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "20") int limit) {

        if (limit < 1 || limit > 100) {
            throw new BusinessException(
                    ErrorCode.INVALID_ARGUMENT,
                    "limit 必须在 1 到 100 之间");
        }

        Long tenantId = TenantContext.requiredTenantId();
        Long userId = Long.valueOf(jwt.getSubject());
        if (!platformAdmins.isAdmin(userId)) {
            roles.require(tenantId, userId, "AUDIT_READ");
        }

        return ApiResponse.success(audit.list(tenantId, limit));
    }

    public record ChangeRoleRequest(
            @NotBlank @Pattern(regexp = "ADMIN|VIEWER") String roleCode
    ) {
    }

    public record AddMemberRequest(
            @NotBlank @Email String email,
            @NotBlank @Pattern(regexp = "ADMIN|VIEWER") String roleCode
    ) {
    }
}
