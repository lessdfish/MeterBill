package com.meterbill.operations.web;

import com.meterbill.common.api.ApiResponse;
import com.meterbill.identity.security.TenantContext;
import com.meterbill.identity.service.TenantRoleService;
import com.meterbill.operations.service.ReconciliationService;
import com.meterbill.operations.service.UsageEventRecoveryService;
import com.meterbill.operations.web.dto.UpdateDifferenceStatusRequest;
import com.meterbill.operations.web.vo.ReconciliationDifferenceVO;
import com.meterbill.operations.web.vo.ReconciliationRunVO;
import com.meterbill.operations.web.vo.UsageEventRetryVO;
import com.meterbill.product.security.PlatAdminGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "对账与异常处理", description = "执行租户对账、查看差异并重试失败任务")
@RestController
@RequiredArgsConstructor
public class ReconciliationController {

    private final ReconciliationService reconciliations;
    private final UsageEventRecoveryService usageEvents;
    private final PlatAdminGuard adminGuard;
    private final TenantRoleService roles;

    @Operation(summary = "执行租户账务对账")
    @PostMapping("/api/v1/tenant/reconciliation/runs")
    public ApiResponse<ReconciliationRunVO> run(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        roles.require(TenantContext.requiredTenantId(), userId(jwt), "RECONCILIATION_RUN");
        return ApiResponse.success(reconciliations.run(
                TenantContext.requiredTenantId(), idempotencyKey, userId(jwt)));
    }

    @Operation(summary = "查询对账批次及差异")
    @GetMapping("/api/v1/tenant/reconciliation/runs/{runId}")
    public ApiResponse<ReconciliationRunVO> getRun(@PathVariable Long runId) {
        return ApiResponse.success(reconciliations.getRun(
                TenantContext.requiredTenantId(), runId));
    }

    @Operation(summary = "平台分页查询对账差异")
    @GetMapping("/api/v1/platform/reconciliation/differences")
    public ApiResponse<List<ReconciliationDifferenceVO>> listDifferences(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "OPEN") String status,
            @RequestParam(defaultValue = "100") int limit) {
        adminGuard.requireAdmin(userId(jwt));
        return ApiResponse.success(reconciliations.listDifferences(status, limit));
    }

    @Operation(summary = "更新对账差异处理状态")
    @PatchMapping("/api/v1/platform/reconciliation/differences/{differenceId}")
    public ApiResponse<ReconciliationDifferenceVO> updateDifference(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long differenceId,
            @Valid @RequestBody UpdateDifferenceStatusRequest request) {
        Long actorId = userId(jwt);
        adminGuard.requireAdmin(actorId);
        return ApiResponse.success(reconciliations.updateDifferenceStatus(
                differenceId, request.status(), request.note(), actorId));
    }

    @Operation(summary = "重试最终失败的用量事件")
    @PostMapping("/api/v1/platform/operations/usage-events/{eventId}/retry")
    public ApiResponse<UsageEventRetryVO> retryUsageEvent(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long eventId) {
        Long actorId = userId(jwt);
        adminGuard.requireAdmin(actorId);
        return ApiResponse.success(usageEvents.retryFinalFailure(eventId, actorId));
    }

    private Long userId(Jwt jwt) {
        return Long.valueOf(jwt.getSubject());
    }
}
