package com.meterbill.payment.web;

import com.meterbill.common.api.ApiResponse;
import com.meterbill.payment.service.SimulatedChannelService;
import com.meterbill.product.security.PlatAdminGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@Tag(name = "模拟支付渠道", description = "记录独立的模拟渠道观察结果，供查询和对账使用")
@RestController
@RequiredArgsConstructor
public class SimulatedChannelController {
    private final SimulatedChannelService channel;
    private final PlatAdminGuard guard;
    @Operation(summary = "记录模拟渠道观察结果")
    @PostMapping("/api/v1/platform/channel/observations")
    public ApiResponse<Void> observe(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Observation r) {
        Long actor=Long.valueOf(jwt.getSubject()); guard.requireAdmin(actor);
        channel.observe(r.tenantId(),r.externalReference(),r.type(),r.currency(),r.amountMinor(),r.status(),actor);
        return ApiResponse.success(null);
    }
    public record Observation(@NotNull @Positive Long tenantId, @NotBlank @Size(max=64) String externalReference,
                              @NotBlank String type, @NotBlank String currency, @NotNull @Positive Long amountMinor,
                              @NotBlank String status) { }
}
