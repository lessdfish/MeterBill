package com.meterbill.payment.web;

import com.meterbill.common.api.ApiResponse;
import com.meterbill.identity.security.TenantContext;
import com.meterbill.identity.service.TenantRoleService;
import com.meterbill.payment.service.PaymentService;
import com.meterbill.payment.web.dto.CreatePaymentRequest;
import com.meterbill.payment.web.dto.CreateRefundRequest;
import com.meterbill.payment.web.dto.SimulateResultRequest;
import com.meterbill.payment.web.vo.PaymentVO;
import com.meterbill.payment.web.vo.RefundVO;
import com.meterbill.product.security.PlatAdminGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@Tag(name = "支付与退款", description = "模拟支付渠道的付款、退款与回调处理")
@RestController
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService payments;
    private final PlatAdminGuard adminGuard;
    private final TenantRoleService roles;

    @Operation(summary = "创建模拟支付")
    @PostMapping("/api/v1/tenant/invoices/{invoiceId}/payments")
    public ApiResponse<PaymentVO> createPayment(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long invoiceId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request) {
        roles.require(TenantContext.requiredTenantId(), userId(jwt), "PAYMENT_MANAGE");
        return ApiResponse.success(payments.createPayment(
                TenantContext.requiredTenantId(), invoiceId, idempotencyKey,
                request.amountMinor(), userId(jwt)));
    }

    @Operation(summary = "查询支付详情")
    @GetMapping("/api/v1/tenant/payments/{paymentId}")
    public ApiResponse<PaymentVO> getPayment(@PathVariable Long paymentId) {
        return ApiResponse.success(payments.getPayment(
                TenantContext.requiredTenantId(), paymentId));
    }

    @Operation(summary = "记录支付渠道回调")
    @PostMapping("/api/v1/platform/payments/{paymentId}/simulate-result")
    public ApiResponse<PaymentVO> simulatePaymentResult(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long paymentId,
            @Valid @RequestBody SimulateResultRequest request) {
        Long actorId = userId(jwt);
        adminGuard.requireAdmin(actorId);
        return ApiResponse.success(payments.simulatePaymentResult(
                paymentId, actorId, request.result()));
    }

    @Operation(summary = "申请退款")
    @PostMapping("/api/v1/tenant/payments/{paymentId}/refunds")
    public ApiResponse<RefundVO> requestRefund(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long paymentId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateRefundRequest request) {
        roles.require(TenantContext.requiredTenantId(), userId(jwt), "PAYMENT_MANAGE");
        return ApiResponse.success(payments.requestRefund(
                TenantContext.requiredTenantId(), paymentId, idempotencyKey,
                request.amountMinor(), userId(jwt)));
    }

    @Operation(summary = "查询退款详情")
    @GetMapping("/api/v1/tenant/refunds/{refundId}")
    public ApiResponse<RefundVO> getRefund(@PathVariable Long refundId) {
        return ApiResponse.success(payments.getRefund(
                TenantContext.requiredTenantId(), refundId));
    }

    @Operation(summary = "记录退款渠道回调")
    @PostMapping("/api/v1/platform/refunds/{refundId}/simulate-result")
    public ApiResponse<RefundVO> simulateRefundResult(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long refundId,
            @Valid @RequestBody SimulateResultRequest request) {
        Long actorId = userId(jwt);
        adminGuard.requireAdmin(actorId);
        return ApiResponse.success(payments.simulateRefundResult(
                refundId, actorId, request.result()));
    }

    private Long userId(Jwt jwt) {
        return Long.valueOf(jwt.getSubject());
    }

    @Operation(summary = "冲正整笔成功支付", description = "仅平台管理员可执行，并保留原支付和账务记录")
    @PostMapping("/api/v1/platform/payments/{paymentId}/reversal")
    public ApiResponse<Long> reverse(@AuthenticationPrincipal Jwt jwt, @PathVariable Long paymentId,
                                     @Valid @RequestBody ReversalRequest request) {
        adminGuard.requireAdmin(userId(jwt));
        return ApiResponse.success(payments.reversePayment(paymentId, userId(jwt), request.reason()));
    }
    @Operation(summary = "主动查询支付渠道结果")
    @PostMapping("/api/v1/platform/payments/{paymentId}/query-channel")
    public ApiResponse<PaymentVO> queryPayment(@AuthenticationPrincipal Jwt jwt, @PathVariable Long paymentId) {
        adminGuard.requireAdmin(userId(jwt));
        return ApiResponse.success(payments.queryPaymentChannel(paymentId, userId(jwt)));
    }
    @Operation(summary = "主动查询退款渠道结果")
    @PostMapping("/api/v1/platform/refunds/{refundId}/query-channel")
    public ApiResponse<RefundVO> queryRefund(@AuthenticationPrincipal Jwt jwt, @PathVariable Long refundId) {
        adminGuard.requireAdmin(userId(jwt));
        return ApiResponse.success(payments.queryRefundChannel(refundId, userId(jwt)));
    }
    public record ReversalRequest(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=500) String reason) { }
}
