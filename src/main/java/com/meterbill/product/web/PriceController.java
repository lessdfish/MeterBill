
package com.meterbill.product.web;

import com.meterbill.common.api.ApiResponse;
import com.meterbill.common.api.PageResponse;
import com.meterbill.product.service.PriceService;
import com.meterbill.product.web.dto.*;
import com.meterbill.product.web.vo.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@Tag(name = "价格版本", description = "维护套餐的月付或年付价格及其版本")
@RestController
@RequiredArgsConstructor
@RequestMapping(
        "/api/v1/products/{productId}/plans/{planId}/prices"
)
public class PriceController {

    private final PriceService prices;

    @Operation(summary = "新增价格定义")
    @PostMapping
    public ApiResponse<Long> createPrice(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long productId,
            @PathVariable Long planId,
            @Valid @RequestBody CreatePriceRequest request) {

        return ApiResponse.success(
                prices.createPrice(userId(jwt), productId, planId, request));
    }

    @Operation(summary = "查询价格定义")
    @GetMapping
    public ApiResponse<PageResponse<PriceVO>> listPrices(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long productId,
            @PathVariable Long planId,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {

        return ApiResponse.success(prices.listPrices(
                userId(jwt), productId, planId, page, size));
    }

    @Operation(summary = "新增草稿价格版本")
    @PostMapping("/{priceId}/versions")
    public ApiResponse<Long> createVersion(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long productId,
            @PathVariable Long planId,
            @PathVariable Long priceId,
            @Valid @RequestBody CreatePriceVersionRequest request) {

        return ApiResponse.success(prices.createVersion(
                userId(jwt), productId, planId, priceId, request));
    }

    @Operation(summary = "查询价格版本")
    @GetMapping("/{priceId}/versions")
    public ApiResponse<PageResponse<PriceVersionVO>> listVersions(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long productId,
            @PathVariable Long planId,
            @PathVariable Long priceId,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {

        return ApiResponse.success(prices.listVersions(
                userId(jwt), productId, planId,
                priceId, page, size));
    }

    @Operation(summary = "修改草稿价格版本")
    @PutMapping("/{priceId}/versions/{versionId}")
    public ApiResponse<PriceVersionVO> update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long productId,
            @PathVariable Long planId,
            @PathVariable Long priceId,
            @PathVariable Long versionId,
            @Valid @RequestBody UpdatePriceVersionRequest request) {

        return ApiResponse.success(prices.updateDraft(
                userId(jwt), productId, planId,
                priceId, versionId, request));
    }

    @Operation(summary = "发布价格版本")
    @PostMapping("/{priceId}/versions/{versionId}/publish")
    public ApiResponse<PriceVersionVO> publish(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long productId,
            @PathVariable Long planId,
            @PathVariable Long priceId,
            @PathVariable Long versionId,
            @Valid @RequestBody PublishPriceVersionRequest request) {

        return ApiResponse.success(prices.publish(
                userId(jwt), productId, planId,
                priceId, versionId, request));
    }

    @Operation(summary = "查询指定时间生效的价格")
    @GetMapping("/{priceId}/effective")
    public ApiResponse<PriceVersionVO> effective(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long productId,
            @PathVariable Long planId,
            @PathVariable Long priceId,
            @RequestParam(required = false) Instant at) {

        return ApiResponse.success(prices.effectiveVersion(
                userId(jwt), productId, planId, priceId, at));
    }

    private Long userId(Jwt jwt) {
        return Long.valueOf(jwt.getSubject());
    }
}
