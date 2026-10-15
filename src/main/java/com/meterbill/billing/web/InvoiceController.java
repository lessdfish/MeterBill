package com.meterbill.billing.web;

import com.meterbill.billing.service.InvoiceService;
import com.meterbill.billing.web.vo.InvoiceAdjustmentVO;
import com.meterbill.billing.web.vo.InvoiceVO;
import com.meterbill.common.api.ApiResponse;
import com.meterbill.identity.security.TenantContext;
import com.meterbill.product.security.PlatAdminGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@Tag(name = "账单管理", description = "生成账单、补记调整并查询账单详情")
@RestController
@RequiredArgsConstructor
public class InvoiceController {

    private final InvoiceService invoices;
    private final PlatAdminGuard adminGuard;

    @Operation(summary = "为已结束订阅账期生成账单")
    @PostMapping("/api/v1/platform/billing/periods/{periodId}/issue")
    public ApiResponse<InvoiceVO> issue(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long periodId) {
        adminGuard.requireAdmin(Long.valueOf(jwt.getSubject()));
        return ApiResponse.success(invoices.issuePeriod(periodId));
    }

    @Operation(summary = "为迟到用量生成账单调整")
    @PostMapping("/api/v1/platform/billing/periods/{periodId}/adjust-late-usage")
    public ApiResponse<InvoiceAdjustmentVO> adjustLateUsage(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long periodId) {
        adminGuard.requireAdmin(Long.valueOf(jwt.getSubject()));
        return ApiResponse.success(invoices.adjustLateUsage(periodId));
    }

    @Operation(summary = "查询账单及其调整记录")
    @GetMapping("/api/v1/tenant/invoices/{invoiceId}")
    public ApiResponse<InvoiceVO> get(@PathVariable Long invoiceId) {
        return ApiResponse.success(
                invoices.getInvoice(TenantContext.requiredTenantId(), invoiceId));
    }
}
