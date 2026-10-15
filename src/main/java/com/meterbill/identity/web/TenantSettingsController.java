package com.meterbill.identity.web;

import com.meterbill.common.api.ApiResponse;
import com.meterbill.identity.service.TenantSettingsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/tenant/settings")
public class TenantSettingsController {
    private final TenantSettingsService settings;

    @GetMapping
    public ApiResponse<TenantSettingsService.Settings> get() {
        return ApiResponse.success(settings.get());
    }

    @PutMapping
    public ApiResponse<TenantSettingsService.Settings> update(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody TenantSettingsService.Update request) {
        return ApiResponse.success(settings.update(Long.valueOf(jwt.getSubject()), request));
    }
}
