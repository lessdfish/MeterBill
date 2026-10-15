package com.meterbill.console.web;

import com.meterbill.common.api.ApiResponse;
import com.meterbill.common.api.PageResponse;
import com.meterbill.console.service.ConsoleQueryService;
import com.meterbill.console.web.vo.ConsoleQueryModels.DashboardOverview;
import com.meterbill.console.web.vo.ConsoleQueryModels.CustomerRow;
import com.meterbill.console.web.vo.ConsoleQueryModels.InvoiceRow;
import com.meterbill.console.web.vo.ConsoleQueryModels.LedgerEntryRow;
import com.meterbill.console.web.vo.ConsoleQueryModels.MemberRow;
import com.meterbill.console.web.vo.ConsoleQueryModels.OperationTask;
import com.meterbill.console.web.vo.ConsoleQueryModels.PaymentInvestigation;
import com.meterbill.console.web.vo.ConsoleQueryModels.PaymentRow;
import com.meterbill.console.web.vo.ConsoleQueryModels.ReconciliationRow;
import com.meterbill.console.web.vo.ConsoleQueryModels.SubscriptionRow;
import com.meterbill.console.web.vo.ConsoleQueryModels.UsageEventRow;
import com.meterbill.console.web.vo.ConsoleQueryModels.AvailablePlan;
import com.meterbill.identity.security.TenantContext;
import com.meterbill.product.security.PlatAdminGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

@Tag(name = "控制台聚合查询", description = "为前端列表、看板和调查页提供租户隔离的只读数据")
@RestController
@RequiredArgsConstructor
public class ConsoleQueryController {

    private final ConsoleQueryService queries;
    private final PlatAdminGuard platformAdmins;

    @Operation(summary = "分页查询平台客户", description = "平台管理员按租户状态、名称或编码查询客户及账务汇总")
    @GetMapping("/api/v1/platform/customers")
    public ApiResponse<PageResponse<CustomerRow>> customers(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        platformAdmins.requireAdmin(Long.valueOf(jwt.getSubject()));
        return ApiResponse.success(queries.customers(page, size, status, keyword));
    }

    @Operation(summary = "查询当前租户运营概览")
    @GetMapping("/api/v1/tenant/dashboard/overview")
    public ApiResponse<DashboardOverview> dashboard() {
        return ApiResponse.success(queries.dashboard(TenantContext.requiredTenantId()));
    }

    @Operation(summary = "分页查询当前租户账单")
    @GetMapping("/api/v1/tenant/invoices")
    public ApiResponse<PageResponse<InvoiceRow>> invoices(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.success(queries.invoices(
                TenantContext.requiredTenantId(), page, size, status, keyword));
    }

    @Operation(summary = "分页查询当前租户支付记录")
    @GetMapping("/api/v1/tenant/payments")
    public ApiResponse<PageResponse<PaymentRow>> payments(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long invoiceId) {
        return ApiResponse.success(queries.payments(
                TenantContext.requiredTenantId(), page, size, status, invoiceId));
    }

    @Operation(summary = "查询支付调查详情")
    @GetMapping("/api/v1/tenant/payments/{paymentId}/investigation")
    public ApiResponse<PaymentInvestigation> paymentInvestigation(@PathVariable Long paymentId) {
        return ApiResponse.success(queries.paymentInvestigation(
                TenantContext.requiredTenantId(), paymentId));
    }

    @Operation(summary = "分页查询当前租户订阅")
    @GetMapping("/api/v1/tenant/subscriptions")
    public ApiResponse<PageResponse<SubscriptionRow>> subscriptions(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String status) {
        return ApiResponse.success(queries.subscriptions(
                TenantContext.requiredTenantId(), page, size, status));
    }

    @Operation(summary = "查询可用于订阅的已发布套餐")
    @GetMapping("/api/v1/tenant/catalog/plans")
    public ApiResponse<List<AvailablePlan>> availablePlans() {
        return ApiResponse.success(queries.availablePlans());
    }

    @Operation(summary = "分页查询当前租户用量事件")
    @GetMapping("/api/v1/tenant/usage-events")
    public ApiResponse<PageResponse<UsageEventRow>> usageEvents(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.success(queries.usageEvents(
                TenantContext.requiredTenantId(), page, size, status, keyword));
    }

    @Operation(summary = "分页查询可重试任务")
    @GetMapping("/api/v1/tenant/operations/tasks")
    public ApiResponse<PageResponse<OperationTask>> tasks(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String status) {
        return ApiResponse.success(queries.tasks(
                TenantContext.requiredTenantId(), page, size, status));
    }

    @Operation(summary = "查询当前租户成员")
    @GetMapping("/api/v1/tenant/security/members")
    public ApiResponse<List<MemberRow>> members() {
        return ApiResponse.success(queries.members(TenantContext.requiredTenantId()));
    }

    @Operation(summary = "分页查询当前租户对账差异")
    @GetMapping("/api/v1/tenant/reconciliation/differences")
    public ApiResponse<List<ReconciliationRow>> differences(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.success(queries.differences(
                TenantContext.requiredTenantId(), status, limit));
    }

    @Operation(summary = "查询当前租户账务分录", description = "可按账单反查账单、调整、支付、退款和冲正产生的分录")
    @GetMapping("/api/v1/tenant/ledger/entries")
    public ApiResponse<List<LedgerEntryRow>> ledgerEntries(
            @RequestParam(required = false) Long invoiceId,
            @RequestParam(required = false) String businessType,
            @RequestParam(defaultValue = "200") int limit) {
        return ApiResponse.success(queries.ledgerEntries(
                TenantContext.requiredTenantId(), invoiceId, businessType, limit));
    }
}
