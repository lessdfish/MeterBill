
package com.meterbill.identity.web;

import com.meterbill.common.api.ApiResponse;
import com.meterbill.identity.security.TenantContext;
import com.meterbill.identity.service.ApiKeyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "用量接入密钥", description = "创建、查看和撤销租户用量接入密钥")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/tenant/api-keys")
public class ApiKeyController {

    private final ApiKeyService apiKeys;

    @Operation(summary = "创建用量接入密钥", description = "密钥明文只在创建成功时返回一次")
    @PostMapping
    public ApiResponse<ApiKeyService.CreatedKey> create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateKeyRequest request) {

        return ApiResponse.success(apiKeys.create(
                TenantContext.requiredTenantId(),
                Long.valueOf(jwt.getSubject()),
                request.name(),
                request.expiresInDays()
        ));
    }

    @Operation(summary = "查询用量接入密钥")
    @GetMapping
    public ApiResponse<List<ApiKeyService.KeyInfo>> list(
            @AuthenticationPrincipal Jwt jwt) {

        return ApiResponse.success(apiKeys.list(
                TenantContext.requiredTenantId(),
                Long.valueOf(jwt.getSubject())
        ));
    }

    @Operation(summary = "撤销用量接入密钥")
    @PostMapping("/{keyId}/revoke")
    public ApiResponse<Void> revoke(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long keyId) {

        apiKeys.revoke(
                TenantContext.requiredTenantId(),
                Long.valueOf(jwt.getSubject()),
                keyId
        );

        return ApiResponse.success(null);
    }

    public record CreateKeyRequest(
            @NotBlank @Size(max = 100) String name,
            @Min(1) @Max(90) int expiresInDays
    ) {
    }
}

