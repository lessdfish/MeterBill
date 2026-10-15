
package com.meterbill.product.web;

import com.meterbill.common.api.ApiResponse;
import com.meterbill.product.service.UsagePricingService;
import com.meterbill.product.web.dto.*;
import com.meterbill.product.web.vo.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "计费规则", description = "维护计量项、用量计费规则并预览费用")
@RestController
@RequiredArgsConstructor
public class UsagePricingController {

    private final UsagePricingService service;

    @Operation(summary = "新增产品计量项")
    @PostMapping("/api/v1/products/{productId}/meters")
    public ApiResponse<Long> createMeter(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long productId,
            @Valid @RequestBody CreateUsageMeterRequest request) {

        return ApiResponse.success(
                service.createMeter(userId(jwt), productId, request));
    }

    @Operation(summary = "查询产品计量项")
    @GetMapping("/api/v1/products/{productId}/meters")
    public ApiResponse<List<UsageMeterVO>> listMeters(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long productId) {

        return ApiResponse.success(
                service.listMeters(userId(jwt), productId));
    }

    @Operation(summary = "新增或更新用量计费规则")
    @PutMapping(
            "/api/v1/products/{productId}/plans/{planId}" +
                    "/prices/{priceId}/versions/{versionId}/usage-rules/{meterId}"
    )
    public ApiResponse<UsageRuleVO> upsertRule(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long productId,
            @PathVariable Long planId,
            @PathVariable Long priceId,
            @PathVariable Long versionId,
            @PathVariable Long meterId,
            @Valid @RequestBody UpsertUsageRuleRequest request) {

        return ApiResponse.success(service.upsertRule(
                userId(jwt), productId, planId,
                priceId, versionId, meterId, request));
    }

    @Operation(summary = "查询价格版本的计费规则")
    @GetMapping(
            "/api/v1/products/{productId}/plans/{planId}" +
                    "/prices/{priceId}/versions/{versionId}/usage-rules"
    )
    public ApiResponse<List<UsageRuleVO>> listRules(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long productId,
            @PathVariable Long planId,
            @PathVariable Long priceId,
            @PathVariable Long versionId) {

        return ApiResponse.success(service.listRules(
                userId(jwt), productId, planId, priceId, versionId));
    }

    @Operation(summary = "预览指定用量的费用")
    @PostMapping(
            "/api/v1/products/{productId}/plans/{planId}" +
                    "/prices/{priceId}/versions/{versionId}" +
                    "/usage-rules/{meterId}/preview"
    )
    public ApiResponse<UsageChargePreviewVO> preview(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long productId,
            @PathVariable Long planId,
            @PathVariable Long priceId,
            @PathVariable Long versionId,
            @PathVariable Long meterId,
            @Valid @RequestBody PreviewUsageRequest request) {

        return ApiResponse.success(service.preview(
                userId(jwt), productId, planId,
                priceId, versionId, meterId, request));
    }

    private Long userId(Jwt jwt) {
        return Long.valueOf(jwt.getSubject());
    }
}
