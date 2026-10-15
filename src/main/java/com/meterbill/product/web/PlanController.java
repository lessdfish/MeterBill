
package com.meterbill.product.web;

import com.meterbill.common.api.ApiResponse;
import com.meterbill.common.api.PageResponse;
import com.meterbill.product.service.PlanService;
import com.meterbill.product.web.dto.CreatePlanRequest;
import com.meterbill.product.web.dto.UpdatePlanRequest;
import com.meterbill.product.web.vo.PlanVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@Tag(name = "产品与套餐", description = "平台管理员维护产品套餐、发布套餐或停用旧套餐")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/products/{productId}/plans")
public class PlanController {

    private final PlanService planService;

    @Operation(summary = "新增套餐", description = "在指定产品下创建一个草稿套餐")
    @PostMapping
    public ApiResponse<Long> create(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long productId,
            @Valid @RequestBody CreatePlanRequest request) {

        return ApiResponse.success(
                planService.create(
                        Long.valueOf(jwt.getSubject()),
                        productId,
                        request
                )
        );
    }

    @Operation(summary = "查询套餐列表", description = "分页查询指定产品下的全部套餐")
    @GetMapping
    public ApiResponse<PageResponse<PlanVO>> list(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long productId,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {

        return ApiResponse.success(
                planService.list(
                        Long.valueOf(jwt.getSubject()),
                        productId,
                        page,
                        size
                )
        );
    }

    @Operation(summary = "查看套餐详情")
    @GetMapping("/{planId}")
    public ApiResponse<PlanVO> detail(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long productId,
            @PathVariable Long planId) {

        return ApiResponse.success(
                planService.detail(
                        Long.valueOf(jwt.getSubject()),
                        productId,
                        planId
                )
        );
    }

    @Operation(summary = "修改草稿套餐")
    @PutMapping("/{planId}")
    public ApiResponse<PlanVO> update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long productId,
            @PathVariable Long planId,
            @Valid @RequestBody UpdatePlanRequest request) {

        return ApiResponse.success(
                planService.update(
                        Long.valueOf(jwt.getSubject()),
                        productId,
                        planId,
                        request
                )
        );
    }

    @Operation(summary = "发布套餐", description = "套餐存在已生效月价后才允许发布")
    @PostMapping("/{planId}/publish")
    public ApiResponse<PlanVO> publish(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long productId,
            @PathVariable Long planId) {
        return ApiResponse.success(planService.publish(
                Long.valueOf(jwt.getSubject()), productId, planId));
    }

    @Operation(
            summary = "停用旧套餐",
            description = "将草稿或已发布套餐标记为已停用，保留历史订阅和账单引用")
    @PostMapping("/{planId}/retire")
    public ApiResponse<PlanVO> retire(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long productId,
            @PathVariable Long planId) {
        return ApiResponse.success(planService.retire(
                Long.valueOf(jwt.getSubject()), productId, planId));
    }
}
