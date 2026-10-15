package com.meterbill.subscription.web;

import com.meterbill.common.api.ApiResponse;
import com.meterbill.identity.security.TenantContext;
import com.meterbill.identity.service.TenantRoleService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import com.meterbill.subscription.service.SubscriptionService;
import com.meterbill.subscription.web.dto.CreateSubscriptionRequest;
import com.meterbill.subscription.web.dto.SchedulePlanChangeRequest;
import com.meterbill.subscription.web.vo.SubscriptionVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "订阅管理", description = "创建、激活、变更和取消租户订阅")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/tenant/subscriptions")
public class SubscriptionController {

    private final SubscriptionService subscriptions;
    private final TenantRoleService roles;

    @Operation(summary = "创建待激活订阅")
    @PostMapping
    public ApiResponse<SubscriptionVO> create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateSubscriptionRequest request) {
        requireManage(jwt);
        return ApiResponse.success(subscriptions.create(
                TenantContext.requiredTenantId(), idempotencyKey, request.planId()));
    }

    @Operation(summary = "查询订阅详情")
    @GetMapping("/{subscriptionId}")
    public ApiResponse<SubscriptionVO> get(@PathVariable Long subscriptionId) {
        return ApiResponse.success(subscriptions.get(
                TenantContext.requiredTenantId(), subscriptionId));
    }

    @Operation(summary = "激活待处理订阅")
    @PostMapping("/{subscriptionId}/activate")
    public ApiResponse<SubscriptionVO> activate(@AuthenticationPrincipal Jwt jwt, @PathVariable Long subscriptionId) {
        requireManage(jwt);
        return ApiResponse.success(subscriptions.activate(
                TenantContext.requiredTenantId(), subscriptionId));
    }

    @Operation(summary = "取消订阅")
    @PostMapping("/{subscriptionId}/cancel")
    public ApiResponse<SubscriptionVO> cancel(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long subscriptionId,
            @RequestParam(defaultValue = "false") boolean immediate) {
        requireManage(jwt);
        return ApiResponse.success(subscriptions.cancel(
                TenantContext.requiredTenantId(), subscriptionId, immediate));
    }

    @Operation(summary = "预约下个账期变更套餐")
    @PostMapping("/{subscriptionId}/plan-changes")
    public ApiResponse<SubscriptionVO> changePlan(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long subscriptionId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody SchedulePlanChangeRequest request) {
        requireManage(jwt);
        return ApiResponse.success(subscriptions.schedulePlanChange(
                TenantContext.requiredTenantId(), subscriptionId,
                idempotencyKey, request.planId()));
    }

    @Operation(summary = "续订已结束账期")
    @PostMapping("/{subscriptionId}/renew")
    public ApiResponse<SubscriptionVO> renew(@AuthenticationPrincipal Jwt jwt, @PathVariable Long subscriptionId) {
        requireManage(jwt);
        return ApiResponse.success(subscriptions.renew(
                TenantContext.requiredTenantId(), subscriptionId));
    }

    private void requireManage(Jwt jwt) {
        roles.require(TenantContext.requiredTenantId(), Long.valueOf(jwt.getSubject()), "SUBSCRIPTION_MANAGE");
    }
}
