package com.meterbill.identity.web;

import com.meterbill.common.api.ApiResponse;
import com.meterbill.identity.persistence.entity.Tenant;
import com.meterbill.identity.persistence.mapper.TenantMapper;
import com.meterbill.identity.security.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ClassName:TenantController
 * Package:com.meterbill.identity.web
 * Description:
 *
 * @Author:lyp
 * @Create:2026/10/10 - 18:21
 * @Version: v1.0
 *
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/tenant")
public class TenantController {

    private final TenantMapper tenantMapper;

    @Operation(summary = "查询当前已验证租户")
    @GetMapping("/current")
    public ApiResponse<TenantView> current(){
        Long tenantId = TenantContext.requiredTenantId();
        Tenant tenant = tenantMapper.selectById(tenantId);

        return ApiResponse.success(new TenantView(tenant.getId(),tenant.getTenantCode(),tenant.getName()));
    }

    public record TenantView(Long id,String tenantCode,String name){}
}
