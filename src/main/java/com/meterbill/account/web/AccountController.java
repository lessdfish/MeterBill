package com.meterbill.account.web;

import com.meterbill.account.service.AccountService;
import com.meterbill.account.web.dto.CreateAccountRequest;
import com.meterbill.account.web.vo.AccountVO;
import com.meterbill.common.api.ApiResponse;
import com.meterbill.identity.security.TenantContext;
import com.meterbill.identity.service.TenantRoleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/tenant/accounts")
public class AccountController {

    private final AccountService accounts;
    private final TenantRoleService roles;

    @PostMapping
    public ApiResponse<AccountVO> create(@AuthenticationPrincipal Jwt jwt,
                                         @Valid @RequestBody CreateAccountRequest request) {
        Long tenantId = TenantContext.requiredTenantId();
        Long actorId = Long.valueOf(jwt.getSubject());
        roles.require(tenantId, actorId, "ACCOUNT_MANAGE");
        return ApiResponse.success(accounts.create(tenantId, actorId, request));
    }

    @GetMapping
    public ApiResponse<List<AccountVO>> list() {
        return ApiResponse.success(accounts.list(TenantContext.requiredTenantId()));
    }

    @GetMapping("/{accountId}")
    public ApiResponse<AccountVO> get(@PathVariable Long accountId) {
        return ApiResponse.success(accounts.get(TenantContext.requiredTenantId(), accountId));
    }
}
